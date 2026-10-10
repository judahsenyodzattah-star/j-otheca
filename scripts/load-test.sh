#!/usr/bin/env bash
# J’otheca smoke-load on the hot read endpoints (Phase 11). Not a stress tool — a repeatable
# latency baseline you run before/after perf changes:
#   BASE_URL=http://localhost:8080 REQUESTS=40 scripts/load-test.sh
# Prints per endpoint: min / avg / p95 / max (client-side, sequential requests).
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
REQUESTS="${REQUESTS:-40}"
ACCRA_UID="LIB-AC2026"

TOKEN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"patron@jotheca.app","password":"Patron12345!"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "${TOKEN:-}" ] || { echo "login failed — is the app running at $BASE_URL?"; exit 1; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

report() {
  sort -n "$2" | awk -v name="$1" '
    { a[NR] = $1 * 1000; sum += $1 * 1000 }
    END {
      i95 = int(NR * 0.95); if (i95 < 1) i95 = 1;
      printf "  %-38s min=%6.1f ms  avg=%6.1f ms  p95=%6.1f ms  max=%6.1f ms  n=%d\n",
             name, a[1], sum / NR, a[i95], a[NR], NR
    }'
}

run() { # label url extra-curl-args...
  local label="$1" url="$2"; shift 2
  : > "$TMP/times"; local fails=0
  for _ in $(seq 1 "$REQUESTS"); do
    local out t code
    out=$(curl -s -o /dev/null -w '%{time_total} %{http_code}' "$@" "$url")
    t="${out% *}"; code="${out#* }"
    [ "$code" -ge 400 ] && fails=$((fails + 1))
    echo "$t" >> "$TMP/times"
  done
  [ "$fails" -eq 0 ] || { echo "  !! $label: $fails/$REQUESTS non-2xx/3xx"; return 1; }
  report "$label" "$TMP/times"
}

echo "J’otheca load baseline — $REQUESTS sequential requests per endpoint against $BASE_URL"
run "GET /tenant/catalog/books (auth search)" \
    "$BASE_URL/api/v1/tenant/catalog/books?q=history&size=20" \
    -H "Authorization: Bearer $TOKEN" -H "X-Library-Id: $ACCRA_UID"
run "GET /libraries (anonymous discovery)" \
    "$BASE_URL/api/v1/libraries?search=accra"
run "GET /public/.../books (public book page)" \
    "$BASE_URL/api/v1/public/libraries/accra-central/books"
run "GET /tenant/ai/search (semantic, metered)" \
    "$BASE_URL/api/v1/tenant/ai/search?q=history%20after%202010" \
    -H "Authorization: Bearer $TOKEN" -H "X-Library-Id: $ACCRA_UID"
echo "done."
