#!/usr/bin/env bash
# Phase 10 live demo: AI surface (semantic search, recommendations, assistant, demand insights).
set -euo pipefail
BASE=http://localhost:8080
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
pass=0; fail=0
check() { if [ "$2" == "$3" ]; then pass=$((pass+1)); echo "  ✔ $1"; else fail=$((fail+1)); echo "  ✘ $1 (expected [$2] got [$3])"; fi; }

echo "== 1. tokens =="
PATRON=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"patron@libranet.app","password":"Patron12345!"}' | J "['accessToken']")
LIBADMIN=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"libadmin@libranet.app","password":"Library12345!"}' | J "['accessToken']")
[ ${#PATRON} -gt 100 ] && [ ${#LIBADMIN} -gt 100 ] && echo "  ✔ tokens" || exit 1

echo "== 2. cold start: embeddings build lazily on first search (Accra) =="
S1=$(curl -s "$BASE/api/v1/tenant/ai/search?q=african%20novel%20colonial%20village&limit=5" -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')
echo "$S1" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print('   candidates:', d['candidateCount'], '| model usage:', d['usage']['used'], '/', d['usage']['limit'])
for h in d['hits'][:3]:
    print('  →', h['title'], '| score', h['score'], '|', '; '.join(h['why']))"
check "top hit is Things Fall Apart" "Things Fall Apart" "$(echo "$S1" | python3 -c "import sys,json;print(json.load(sys.stdin)['hits'][0]['title'])")"

echo "== 3. structured filter: 'history books after 2010' excludes older titles =="
S2=$(curl -s "$BASE/api/v1/tenant/ai/search?q=history%20after%202010&limit=5" -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')
echo "$S2" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print('   filters:', d['filters'])
for h in d['hits']: print('  →', h['title'], h['publishedYear'])"
check "year filter parsed (after 2010)" 2010 "$(echo "$S2" | python3 -c "import sys,json;print(json.load(sys.stdin)['filters']['publishedAfter'])")"
check "no pre-2011 hit leaked" yes "$(echo "$S2" | python3 -c "import sys,json;d=json.load(sys.stdin);print('yes' if all(h['publishedYear'] is None or h['publishedYear']>2010 for h in d['hits']) else 'no')")"

echo "== 4. isolation: Pentecost cannot smell Accra's titles =="
S3=$(curl -s "$BASE/api/v1/tenant/ai/search?q=african%20novel%20colonial%20village&limit=5" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$S3" | python3 -c "import sys,json;d=json.load(sys.stdin);print('   Pentecost hits:', [h['title'] for h in d['hits']])"
check "zero cross-tenant hits" no "$(echo "$S3" | python3 -c "import sys,json;d=json.load(sys.stdin);print('yes' if any(h['title'] in ('Things Fall Apart','Sapiens') for h in d['hits']) else 'no')")"

echo "== 5. recommendations for the patron (Accra history: seeded loan/ratings) =="
R=$(curl -s "$BASE/api/v1/tenant/ai/recommendations?limit=5" -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')
echo "$R" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print('   basis:', d['basis'])
for r in d['recommendations'][:4]: print('  →', r['title'], '|', '; '.join(r['because']) or 'semantic match')"
check "recommendations returned" yes "$(echo "$R" | python3 -c "import sys,json;print('yes' if len(json.load(sys.stdin)['recommendations'])>0 else 'no')")"

echo "== 6. guarded assistant (staff only) =="
check "patron refused (403)" 403 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tenant/ai/assistant/ask" -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026' -H 'Content-Type: application/json' -d '{"question":"what should we buy?"}')"
A1=$(curl -s -X POST "$BASE/api/v1/tenant/ai/assistant/ask" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' -H 'Content-Type: application/json' -d '{"question":"what should we buy more copies of?"}')
echo "$A1" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  [',d['intent'],']',d['answer'][:150],'…')"
check "DEMAND intent answered" DEMAND "$(echo "$A1" | J "['intent']")"
A2=$(curl -s -X POST "$BASE/api/v1/tenant/ai/assistant/ask" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' -H 'Content-Type: application/json' -d '{"question":"do we have anything about leadership?"}')
check "SEARCH intent answered" SEARCH "$(echo "$A2" | J "['intent']")"
echo "$A2" | python3 -c "import sys,json;d=json.load(sys.stdin);print('   citations:', [c['title'] for c in d['citations']])"
check "question too short (400)" 400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tenant/ai/assistant/ask" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026' -H 'Content-Type: application/json' -d '{"question":"hi"}')"

echo "== 7. demand insights (staff) =="
D=$(curl -s "$BASE/api/v1/tenant/ai/demand-insights" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$D" | python3 -m json.tool | head -24
check "insights answer with summary" yes "$(echo "$D" | python3 -c "import sys,json;print('yes' if len(json.load(sys.stdin)['summary'])>0 else 'no')")"

echo "== 8. usage metering + rebuild (admin) =="
U=$(curl -s "$BASE/api/v1/tenant/ai/usage" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
echo "$U" | python3 -m json.tool
check "usage counted ≥2 for Pentecost (search + assistant ×2 + insights)" yes "$(echo "$U" | python3 -c "import sys,json;print('yes' if json.load(sys.stdin)['used']>=2 else 'no')")"
RB=$(curl -s -X POST "$BASE/api/v1/tenant/ai/embeddings/rebuild" -H "Authorization: Bearer $LIBADMIN" -H 'X-Library-Id: LIB-PU2026')
check "rebuild returns count + model" yes "$(echo "$RB" | python3 -c "import sys,json;d=json.load(sys.stdin);print('yes' if d['model']=='local-hash-256' and d['embeddings']>=3 else 'no')")"
check "patron cannot rebuild (403)" 403 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tenant/ai/embeddings/rebuild" -H "Authorization: Bearer $PATRON" -H 'X-Library-Id: LIB-AC2026')"

echo "== 9. unauthenticated requests refused =="
check "no token → 401" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/tenant/ai/search?q=x" -H 'X-Library-Id: LIB-AC2026')"

echo; echo "=== PASS $pass / FAIL $fail ==="