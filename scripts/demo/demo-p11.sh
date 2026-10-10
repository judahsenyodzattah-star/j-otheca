#!/usr/bin/env bash
# Phase-11 hardening demo — cache headers, OpenAPI envelope, error docs. Needs the app on 8080.
set -uo pipefail
B="${BASE_URL:-http://localhost:8080}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
have() { [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "$2" ]; }

echo "== 1. anonymous plane advertises short public caching =="
HERO=$(curl -s "$B/api/v1/libraries" -D /tmp/p11.h >/dev/null; grep -i '^cache-control' /tmp/p11.h)
echo "$HERO" | grep -q "max-age=60" && echo "$HERO" | grep -qi public && ok "discovery list: $HERO" || bad "discovery cache header: $HERO"
SLUG=$(curl -s "$B/api/v1/public/libraries/accra-central/books?q=things" | python3 -c "import sys,json;print(json.load(sys.stdin)['content'][0]['slug'])")
D=$(curl -s "$B/api/v1/public/libraries/accra-central/books/$SLUG" -D /tmp/p11.h >/dev/null; grep -i '^cache-control' /tmp/p11.h)
echo "$D" | grep -q "max-age=60" && ok "public book detail: $D" || bad "public detail header: $D"
J=$(curl -s "$B/api/v1/join/ASH-2026" -D /tmp/p11.h >/dev/null; grep -i '^cache-control' /tmp/p11.h)
echo "$J" | grep -q "no-store" && ok "invitation preview: $J" || bad "invitation header: $J"

echo "== 2. private tenant data is never public-cacheable =="
TOKEN=$(curl -s -X POST "$B/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"patron@jotheca.app","password":"Patron12345!"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s "$B/api/v1/tenant/catalog/books" -H "Authorization: Bearer $TOKEN" -H "X-Library-Id: LIB-AC2026" -D /tmp/p11.h >/dev/null
if grep -i '^cache-control' /tmp/p11.h | grep -q public; then bad "tenant catalog publicly cacheable"; else ok "tenant catalog has no public caching"; fi

echo "== 3. OpenAPI polish =="
curl -s "$B/v3/api-docs" > /tmp/p11docs.json
python3 - <<'EOF' && ok "api-docs contract" || bad "api-docs contract"
import json, sys
d = json.load(open('/tmp/p11docs.json'))
assert set(d['components']['schemas']['ApiError']['properties']) == {'timestamp','status','code','message','details','traceId'}
login = d['paths']['/api/v1/auth/login']['post']['responses']
assert {'400','401','423','429'} <= set(login), login
ai = d['paths']['/api/v1/tenant/ai/search']['get']
assert ai['summary'] == 'Semantic catalog search'
assert {'400','401','403'} <= set(ai['responses']) and '423' not in ai['responses']
assert '401' not in d['paths']['/api/v1/libraries']['get']['responses']
EOF

echo "== 4. health =="
have "$B/actuator/health" 200 && ok "health UP" || bad "health"

echo; echo "=== PASS $PASS / FAIL $FAIL ==="
[ "$FAIL" -eq 0 ]
