#!/usr/bin/env bash
# Phase 9 live demo: quotas/billing/analytics against the running dev server.
set -euo pipefail
BASE=http://localhost:8080
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

pass=0; fail=0
check() { # name expected actual
  if [ "$2" == "$3" ]; then pass=$((pass+1)); echo "  ✔ $1";
  else fail=$((fail+1)); echo "  ✘ $1 (expected [$2] got [$3])"; fi
}

echo "== 1. logins =="
LIBADMIN=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"libadmin@libranet.app","password":"Library12345!"}' | J "['accessToken']")
ACC=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"accraadmin@libranet.app","password":"Library12345!"}' | J "['accessToken']")
PATRON=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"patron@libranet.app","password":"Patron12345!"}' | J "['accessToken']")
SUPER=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@libranet.app","password":"Admin12345!"}' | J "['accessToken']")
[ ${#LIBADMIN} -gt 100 ] && [ ${#SUPER} -gt 100 ] && echo "  ✔ four tokens issued" || exit 1

echo "== 2. subscription view (Pentecost / PROFESSIONAL) =="
SUB=$(curl -s $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$SUB" | python3 -m json.tool | head -14
check "plan is PROFESSIONAL" PROFESSIONAL "$(echo "$SUB" | J "['planCode']")"
ADDON_BEFORE=$(echo "$SUB" | J "['storageAddonGb']")
check "storage cap = 50 + addons" $((50 + ADDON_BEFORE)) "$(echo "$SUB" | J "['effectiveLimits']['storageGb']")"
check "patron is refused (403)" 403 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')"

echo "== 3. renewal checkout → dev mark-paid → +30 days =="
END_BEFORE=$(echo "$SUB" | J "['periodEnd']")
CHECKOUT=$(curl -s $BASE/api/v1/tenant/billing/renew -X POST -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
REF=$(echo "$CHECKOUT" | J "['reference']")
check "renewal amount = GH₵149.00 (14900) " 14900 "$(echo "$CHECKOUT" | J "['amountMinor']")"
curl -s $BASE/api/v1/billing/dev/mark-paid -H 'Content-Type: application/json' -d "{\"reference\":\"$REF\"}" | J "['status']" >/dev/null
END_AFTER=$(curl -s $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' | J "['periodEnd']")
check "period extended ~30d" yes "$(python3 -c "
from datetime import datetime
b=datetime.fromisoformat('$END_BEFORE'.replace('Z','+00:00')); a=datetime.fromisoformat('$END_AFTER'.replace('Z','+00:00'))
print('yes' if (a-b).days>=29 else 'no')")"

echo "== 4. storage add-on 3 GB → effective cap 53 GB =="
ADDON=$(curl -s $BASE/api/v1/tenant/billing/storage-addon -X POST -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' -H 'Content-Type: application/json' -d '{"gb":3}')
AREF=$(echo "$ADDON" | J "['reference']")
curl -s $BASE/api/v1/billing/dev/mark-paid -H 'Content-Type: application/json' -d "{\"reference\":\"$AREF\"}" | J "['status']" >/dev/null
SUB2=$(curl -s $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
check "storageAddonGb +3" $((ADDON_BEFORE + 3)) "$(echo "$SUB2" | J "['storageAddonGb']")"
check "effective storage 50+addons" $((50 + ADDON_BEFORE + 3)) "$(echo "$SUB2" | J "['effectiveLimits']['storageGb']")"

echo "== 5. storage add-on validation =="
check "gb=0 rejected (400)" 400 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/tenant/billing/storage-addon -X POST -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' -H 'Content-Type: application/json' -d '{"gb":0}')"

echo "== 6. platform plan change Pentecost → STARTER (usage fits) → back to PRO =="
PENT_ID=$(curl -s "$BASE/api/v1/platform/libraries?status=ACTIVE" -H "Authorization: Bearer $SUPER" | python3 -c "import sys,json;d=json.load(sys.stdin);print([l['id'] for l in d['content'] if l['uid']=='LIB-PU2026'][0])")
CHG=$(curl -s $BASE/api/v1/platform/libraries/$PENT_ID/plan -X POST -H "Authorization: Bearer $SUPER" -H 'Content-Type: application/json' -d '{"planCode":"STARTER","reason":"demo downgrade"}')
check "direction DOWNGRADE" DOWNGRADE "$(echo "$CHG" | J "['direction']")"
CAP=$(curl -s $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' | J "['effectiveLimits']['storageGb']")
check "STARTER+addons = 5+addons GB" $((5 + ADDON_BEFORE + 3)) "$CAP"
curl -s $BASE/api/v1/platform/libraries/$PENT_ID/plan -X POST -H "Authorization: Bearer $SUPER" -H 'Content-Type: application/json' -d '{"planCode":"PROFESSIONAL"}' | J "['direction']" >/dev/null
CAP2=$(curl -s $BASE/api/v1/tenant/billing/subscription -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' | J "['effectiveLimits']['storageGb']")
check "restored PROFESSIONAL = 50+addons GB" $((50 + ADDON_BEFORE + 3)) "$CAP2"
check "plan change to same plan refused (400)" 400 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/platform/libraries/$PENT_ID/plan -X POST -H "Authorization: Bearer $SUPER" -H 'Content-Type: application/json' -d '{"planCode":"PROFESSIONAL"}')"

echo "== 7. patron pays a fine online end-to-end =="
MID=$(curl -s "$BASE/api/v1/tenant/circulation/members/lookup?number=MBR-AC0001" -H "Authorization: Bearer $ACC" -H 'X-Library-Id: LIB-AC2026' | J "['membershipId']")
FINE=$(curl -s $BASE/api/v1/tenant/circulation/fines -X POST -H "Authorization: Bearer $ACC" -H 'X-Library-Id: LIB-AC2026' -H 'Content-Type: application/json' -d "{\"membershipId\":\"$MID\",\"type\":\"MANUAL\",\"amountMinor\":1500,\"note\":\"damaged sleeve (demo)\"}")
FID=$(echo "$FINE" | J "['id']")
check "fine assessed GHS 15.00" 1500 "$(echo "$FINE" | J "['amountMinor']")"
check "cross-tenant pay-online hidden (404)" 404 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/tenant/circulation/fines/$FID/pay-online -X POST -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')"
CO=$(curl -s $BASE/api/v1/tenant/circulation/fines/$FID/pay-online -X POST -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')
FREF=$(echo "$CO" | J "['reference']")
check "checkout opened for 1500" 1500 "$(echo "$CO" | J "['amountMinor']")"
check "bogus reference 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/tenant/circulation/fines/$FID/confirm-online -X POST -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026' -H 'Content-Type: application/json' -d '{"reference":"nope"}')"
DONE=$(curl -s $BASE/api/v1/tenant/circulation/fines/$FID/confirm-online -X POST -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026' -H 'Content-Type: application/json' -d "{\"reference\":\"$FREF\"}")
check "fine settled online (PAID)" PAID "$(echo "$DONE" | J "['status']")"
echo "$DONE" | J "['settlement']" | sed 's/^/  settlement: /'
check "re-confirm refused (409)" 409 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/tenant/circulation/fines/$FID/confirm-online -X POST -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026' -H 'Content-Type: application/json' -d "{\"reference\":\"$FREF\"}")"

echo "== 8. three dashboards =="
TEN=$(curl -s $BASE/api/v1/tenant/analytics/dashboard -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$TEN" | python3 -m json.tool | head -22
check "tenant dash: 3 books" 3 "$(echo "$TEN" | J "['books']['used']")"
check "tenant dash: 1 branch" 1 "$(echo "$TEN" | J "['branches']['used']")"
check "tenant dash: storage cap tracks add-ons" $(((50 + ADDON_BEFORE + 3)*1024*1024*1024)) "$(echo "$TEN" | J "['storageBytes']['limit']")"
ME=$(curl -s $BASE/api/v1/tenant/analytics/me -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')
check "member dash role PATRON" PATRON "$(echo "$ME" | J "['role']")"
check "member dash open fines 0 (settled)" 0 "$(echo "$ME" | J "['openFinesMinor']")"
PLAT=$(curl -s $BASE/api/v1/platform/analytics/dashboard -H "Authorization: Bearer $SUPER")
echo "$PLAT" | python3 -m json.tool | sed -n 1,30p
check "MRR ≥ GHS 646 (PRO 149 + STARTER 49 + ...)" yes "$(echo "$PLAT" | python3 -c "import sys,json;d=json.load(sys.stdin);print('yes' if d['mrr']['mrrMinor']>=64600 else 'no')")"
check "tenant refused platform dash (403)" 403 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/v1/platform/analytics/dashboard -H "Authorization: Bearer $LIBADMIN")"

echo "== 9. tenant payment history =="
HIST=$(curl -s $BASE/api/v1/tenant/billing/payments -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$HIST" | python3 -m json.tool | head -20
check "history has SUBSCRIPTION + STORAGE_ADDON" yes "$(echo "$HIST" | python3 -c "import sys,json;d=json.load(sys.stdin);p={r['purpose'] for r in d};print('yes' if {'SUBSCRIPTION','STORAGE_ADDON'}<=p else 'no')")"

echo
echo "=== PASS $pass / FAIL $fail ==="