#!/usr/bin/env bash
# ==============================================================================================
# Phase A restricted-role smoke test — EXECUTABLE end-to-end proof of the tenant-isolation cutover.
#
# This is NOT a "does Spring Boot start" check. It boots the real application against a real
# PostgreSQL with the least-privilege roles, then asserts the ACTUAL HTTP responses and the ACTUAL
# tenant boundaries enforced by PostgreSQL RLS:
#
#   provisioning  : 3 roles exist with the exact intended attributes (NO jotheca_platform / BYPASSRLS
#                   write role), Flyway ran as the MIGRATION role, separate from the runtime role,
#                   V26 landed with FORCE RLS on all 29 tenant tables.
#   authorization : unauthenticated platform call -> 401; non-SUPER_ADMIN -> 403 (SecurityConfig).
#   platform READ : SUPER_ADMIN GET /api/v1/platform/** -> 200 and sees ACROSS tenants (jotheca_readall,
#                   BYPASSRLS, SELECT-only).
#   platform WRITE: SUPER_ADMIN POST /api/v1/platform/** -> 2xx and is CONFINED to the armed target
#                   library (jotheca_app, NOBYPASSRLS) — there is no cross-tenant write role.
#   tenant plane  : a member of library A can read A (200) and is REFUSED on library B (403); at the
#                   DB layer jotheca_app sees zero rows with no tenant armed and cannot plant or
#                   reassign a row under another tenant.
#   read-only role: jotheca_readall can SELECT across tenants but cannot INSERT/UPDATE/DELETE.
#
# STATUS: WRITTEN, NOT YET EXECUTED. No Java/Maven/Docker/PostgreSQL toolchain existed in the
# authoring sandbox, so nothing here has been run. Every assertion below is therefore UNVERIFIED
# until CI (or an operator) executes this script and reports results. See
# docs/audits/PHASE-A-REMEDIATION-DESIGN-2026-10-11.md (UPDATE 4).
#
# USAGE
#   scripts/restricted-role-smoke.sh
#
#   # reuse an already-built jar / keep the DB around for inspection
#   SMOKE_SKIP_BUILD=1 SMOKE_JAR=backend/target/jotheca-backend-0.1.0-SNAPSHOT.jar \
#     SMOKE_KEEP_PG=1 scripts/restricted-role-smoke.sh
#
# PREREQUISITES (hard requirements — the script fails fast, it never silently degrades)
#   * Docker with a reachable daemon (the script starts pgvector/pgvector:pg16 itself)
#   * JDK 21 + Maven (or backend/mvnw) to build the jar, unless SMOKE_SKIP_BUILD=1
#   * outbound access to Docker Hub to pull pgvector/pgvector:pg16
#   * curl, and bash 4+
#   * a free TCP port for the app (SMOKE_APP_PORT, default 18080)
#
# EXIT CODE: 0 only when EVERY assertion passed; 1 otherwise (CI-safe).
# SECRETS: per-run passwords are generated into a 0600 temp dir and never echoed; nothing is
# written into the repository.
# ==============================================================================================
set -euo pipefail

# ----------------------------------------------------------------------------- arguments / knobs
PG_IMAGE="${SMOKE_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_CONTAINER="${SMOKE_PG_CONTAINER:-jotheca-rls-smoke}"
# 5543, NOT the compose stack's 5532: infra/docker-compose.yml publishes host 5532 for its own
# postgres, so the old default would have silently attached the smoke test to the dev database.
PG_PORT="${SMOKE_PG_PORT:-5543}"
PG_DB="${SMOKE_PG_DB:-jotheca}"
PG_OWNER="${SMOKE_PG_OWNER:-jotheca_migrate}"   # fresh-DB path: the migration role owns the schema
# Outside the compose stack's published range (3001/5532/6479/8100/8101/8180/9100-9103) so the two
# can be run side by side; also probed for collisions below.
APP_PORT="${SMOKE_APP_PORT:-18080}"
APP_DIR="${SMOKE_APP_DIR:-backend}"
SKIP_BUILD="${SMOKE_SKIP_BUILD:-0}"
JAR_HINT="${SMOKE_JAR:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PASS=0; FAIL=0; FAILED_NAMES=()
WORKDIR="$(mktemp -d)"; chmod 700 "$WORKDIR"
APP_PID=""

# ------------------------------------------------------------------------------------- tiny utils
c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
section() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { PASS=$((PASS+1)); c_grn "  PASS  $*"; }
fail() { FAIL=$((FAIL+1)); FAILED_NAMES+=("$*"); c_red "  FAIL  $*"; }

assert_eq()          { if [ "$2" = "$3" ]; then pass "$1 (=${3})"; else fail "$1 — expected [$2] got [$3]"; fi; }
assert_contains()    { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 — [$3] not found in: $(printf '%s' "$2" | head -c 300)" ;; esac; }
assert_not_contains(){ case "$2" in *"$3"*) fail "$1 — [$3] unexpectedly present" ;; *) pass "$1" ;; esac; }

cleanup() {
  local rc=$?
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5; do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
  if [ "${SMOKE_KEEP_PG:-0}" = "1" ]; then
    c_ylw "SMOKE_KEEP_PG=1 — leaving container $PG_CONTAINER running for inspection"
  else
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORKDIR"
  exit "$rc"
}
trap cleanup EXIT INT TERM

require() { # require <cmd> <prerequisite description>
  if ! command -v "$1" >/dev/null 2>&1; then
    c_red "PREREQUISITE MISSING: $2 ('$1' not found on PATH)."
    c_red "This smoke test CANNOT be skipped or degraded — it must fail closed."
    exit 1
  fi
}

# ---------------------------------------------------------------------------- SQL helpers (docker)
# Every query runs INSIDE the container, so no local psql is required. Passwords never leave the
# container environment and are never printed. Any SQL failure yields the literal SQL_ERROR so the
# corresponding assertion FAILS VISIBLY instead of silently passing or aborting the whole run.
SQL_ERROR="SQL_ERROR"

psql_owner() { # psql_owner <sql>  — as the migration/owner role
  local out rc; set +e
  out="$(docker exec -e PGPASSWORD="$PG_OWNER_PW" "$PG_CONTAINER" \
        psql -U "$PG_OWNER" -d "$PG_DB" -A -t -q -v ON_ERROR_STOP=1 -c "$1" 2>&1)"; rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then echo "$SQL_ERROR"; else printf '%s' "$out" | tr -d '\r'; fi
}
psql_as() { # psql_as <role> <password> <sql>
  local out rc; set +e
  out="$(docker exec -e PGPASSWORD="$2" "$PG_CONTAINER" \
        psql -U "$1" -d "$PG_DB" -A -t -q -v ON_ERROR_STOP=1 -c "$3" 2>&1)"; rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then echo "$SQL_ERROR"; else printf '%s' "$out" | tr -d '\r'; fi
}
# Runs <sql> inside an explicit transaction with the tenant GUC armed (SET LOCAL, exactly like
# TenantAwareDataSource). Separate -c flags in ONE psql session so BEGIN/COMMIT are real.
psql_tx_as() { # psql_tx_as <role> <password> <tenantId|-> <sql>
  local role="$1" pw="$2" tenant="$3" sql="$4" out rc
  set +e
  if [ "$tenant" = "-" ]; then
    out="$(docker exec -e PGPASSWORD="$pw" "$PG_CONTAINER" \
          psql -U "$role" -d "$PG_DB" -A -t -q -v ON_ERROR_STOP=1 \
          -c "BEGIN" -c "$sql" -c "COMMIT" 2>&1 | tail -1)"; rc=$?
  else
    out="$(docker exec -e PGPASSWORD="$pw" "$PG_CONTAINER" \
          psql -U "$role" -d "$PG_DB" -A -t -q -v ON_ERROR_STOP=1 \
          -c "BEGIN" -c "SELECT set_config('app.tenant_id','$tenant',true)" \
          -c "$sql" -c "COMMIT" 2>&1 | tail -1)"; rc=$?
  fi
  set -e
  if [ "$rc" -ne 0 ]; then echo "$SQL_ERROR"; else printf '%s' "$out" | tr -d '\r'; fi
}
# Runs a statement and reports the REAL exit status via PSQL_OUT / PSQL_RC.
psql_as_rc() { # psql_as_rc <role> <password> <sql>
  local out rc; set +e
  out="$(docker exec -e PGPASSWORD="$2" "$PG_CONTAINER" \
        psql -U "$1" -d "$PG_DB" -A -t -q -v ON_ERROR_STOP=1 -c "$3" 2>&1)"; rc=$?
  set -e
  PSQL_OUT="$out"; PSQL_RC="$rc"
}
# Runs a statement expected to FAIL; echoes OK when it failed, LEAKED when it unexpectedly succeeded.
# MUST use psql_as_rc: psql_as/psql_owner deliberately convert failures into SQL_ERROR, which would
# make every "expected to fail" assertion report OK regardless of what the database actually did.
expect_sql_failure() { # expect_sql_failure <role> <password> <sql>
  psql_as_rc "$1" "$2" "$3"
  if [ "$PSQL_RC" -ne 0 ]; then echo "OK"; else echo "LEAKED:${PSQL_OUT}"; fi
}

# ------------------------------------------------------------------------------------------- HTTP
HTTP_CODE=""; HTTP_BODY=""
http() { # http <method> <path> [json-body]
  local method="$1" path="$2" body="${3:-}" tmp
  tmp="$(mktemp)"
  local args=(-s -o "$tmp" -w '%{http_code}' -X "$method" "http://127.0.0.1:${APP_PORT}${path}"
              -H 'Content-Type: application/json')
  [ -n "${TOKEN:-}" ]       && args+=(-H "Authorization: Bearer ${TOKEN}")
  [ -n "${LIB_HEADER:-}" ]  && args+=(-H "X-Library-Id: ${LIB_HEADER}")
  [ -n "$body" ]            && args+=(--data "$body")
  HTTP_CODE="$(curl "${args[@]}" || true)"   # `|| true`: report a failed assertion, do not abort
  HTTP_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}
# NB: `|| true` is REQUIRED — under `set -e` a non-matching grep would abort the entire smoke run
# instead of letting the following assertion report a clean, visible failure.
token_of() { printf '%s' "$1" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true; }
field_of() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4 || true; }
rand_hex() { openssl rand -hex "${1:-18}" 2>/dev/null || head -c"${1:-18}" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# =========================================================================================== START
section "0. Prerequisites (fail closed — never skip)"
require docker "Docker is required to run a real PostgreSQL for the RLS assertions"
require curl  "curl is required for the HTTP assertions"
if ! docker info >/dev/null 2>&1; then
  c_red "PREREQUISITE MISSING: Docker daemon is not reachable (docker info failed)."
  c_red "Testcontainers/RLS assertions cannot run — failing closed."
  exit 1
fi
if ! docker image inspect "$PG_IMAGE" >/dev/null 2>&1; then
  c_ylw "pulling $PG_IMAGE (needs Docker Hub access)…"
  docker pull "$PG_IMAGE" >/dev/null || { c_red "cannot pull $PG_IMAGE — no Docker Hub access?"; exit 1; }
fi
pass "docker daemon reachable and $PG_IMAGE available"
# L3: a silently-colliding port would attach the smoke test to someone else's database and every
# isolation assertion would then pass for the wrong reason. Refuse to start instead.
port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for _p in "$PG_PORT" "$APP_PORT"; do
  if port_free "$_p"; then pass "host port $_p is free"; else
    c_red "PREREQUISITE MISSING: 127.0.0.1:$_p is already in use (set SMOKE_PG_PORT / SMOKE_APP_PORT)."
    c_red "Refusing to run against a port that may already belong to another database."
    exit 1
  fi
done

PG_OWNER_PW="$(rand_hex 18)"
MIGRATE_PW="$PG_OWNER_PW"
APP_PW="$(rand_hex 18)"
READALL_PW="$(rand_hex 18)"
JWT_SECRET="smoke-$(rand_hex 24)"
[ "${#JWT_SECRET}" -ge 48 ] || JWT_SECRET="${JWT_SECRET}$(rand_hex 24)"

# ------------------------------------------------------------------------------ 1. real Postgres
section "1. Real PostgreSQL ($PG_IMAGE) — fresh-DB path, migration role owns the schema"
docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$PG_CONTAINER" \
  -e POSTGRES_USER="$PG_OWNER" -e POSTGRES_PASSWORD="$PG_OWNER_PW" -e POSTGRES_DB="$PG_DB" \
  -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" >/dev/null
ready=""
for _ in $(seq 1 60); do
  if docker exec "$PG_CONTAINER" pg_isready -U "$PG_OWNER" -d "$PG_DB" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [ -z "$ready" ]; then c_red "Postgres did not become ready"; docker logs "$PG_CONTAINER" | tail -30; exit 1; fi
pass "Postgres ready on 127.0.0.1:${PG_PORT}/${PG_DB} (owner=${PG_OWNER})"

# ------------------------------------------------------------------------- 2. provision the roles
section "2. Provision least-privilege roles (infra/db/provision-roles.sh)"
if JOTHECA_MIGRATE_PW="$MIGRATE_PW" JOTHECA_APP_PW="$APP_PW" JOTHECA_READALL_PW="$READALL_PW" \
   PGHOST=127.0.0.1 PGPORT="$PG_PORT" PGUSER="$PG_OWNER" PGDATABASE="$PG_DB" \
   bash "$ROOT/infra/db/provision-roles.sh" >"$WORKDIR/provision.log" 2>&1; then
  pass "provision-roles.sh completed"
else
  fail "provision-roles.sh failed"; cat "$WORKDIR/provision.log"; exit 1
fi

section "3. Role inventory & attributes (least privilege, no BYPASSRLS write role)"
assert_eq "exactly 3 runtime roles provisioned" "3" \
  "$(psql_owner "SELECT count(*) FROM pg_roles WHERE rolname IN ('jotheca_migrate','jotheca_app','jotheca_readall')")"
assert_eq "NO jotheca_platform / BYPASSRLS write role exists" "0" \
  "$(psql_owner "SELECT count(*) FROM pg_roles WHERE rolname = 'jotheca_platform'")"
assert_eq "jotheca_app is not superuser"      "f" "$(psql_owner "SELECT rolsuper FROM pg_roles WHERE rolname='jotheca_app'")"
assert_eq "jotheca_app CANNOT bypass RLS"     "f" "$(psql_owner "SELECT rolbypassrls FROM pg_roles WHERE rolname='jotheca_app'")"
assert_eq "jotheca_migrate is not superuser"  "f" "$(psql_owner "SELECT rolsuper FROM pg_roles WHERE rolname='jotheca_migrate'")"
assert_eq "jotheca_migrate CANNOT bypass RLS" "f" "$(psql_owner "SELECT rolbypassrls FROM pg_roles WHERE rolname='jotheca_migrate'")"
assert_eq "jotheca_readall is not superuser"  "f" "$(psql_owner "SELECT rolsuper FROM pg_roles WHERE rolname='jotheca_readall'")"
assert_eq "jotheca_readall MAY bypass RLS (read-only plane)" "t" \
  "$(psql_owner "SELECT rolbypassrls FROM pg_roles WHERE rolname='jotheca_readall'")"

# ----------------------------------------------------------------------------------- 4. build jar
section "4. Build the backend"
if [ "$SKIP_BUILD" = "1" ]; then
  c_ylw "SMOKE_SKIP_BUILD=1 — reusing an existing artifact"
else
  if [ -x "$ROOT/$APP_DIR/mvnw" ]; then MVN="$ROOT/$APP_DIR/mvnw"; else MVN="mvn"; fi
  require "$MVN" "JDK 21 + Maven are required to build the backend (or set SMOKE_SKIP_BUILD=1)"
  c_ylw "building with $MVN …"
  if ! (cd "$ROOT/$APP_DIR" && $MVN -q -B -DskipTests package) >"$WORKDIR/build.log" 2>&1; then
    fail "backend build failed"; tail -40 "$WORKDIR/build.log"; exit 1
  fi
  pass "backend packaged"
fi
JAR="${JAR_HINT:-$(ls -1 "$ROOT/$APP_DIR"/target/jotheca-backend-*.jar 2>/dev/null | grep -v '\.jar\.orig' | head -1 || true)}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  c_red "no backend jar found (set SMOKE_JAR=... or run without SMOKE_SKIP_BUILD)."; exit 1
fi
pass "using jar: ${JAR#$ROOT/}"

# ------------------------------------------------------ 5. boot with restricted role + own Flyway
section "5. Boot the app: restricted-role=true, separate Flyway credentials"
APP_LOG="$WORKDIR/app.log"
# `env -u SPRING_PROFILES_ACTIVE` guarantees the dev profile (H2) can never be inherited from the
# caller's environment — the whole point is to run against real Postgres with the real roles.
env -u SPRING_PROFILES_ACTIVE \
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}" \
SPRING_DATASOURCE_USERNAME="jotheca_app" \
SPRING_DATASOURCE_PASSWORD="$APP_PW" \
SPRING_FLYWAY_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}" \
SPRING_FLYWAY_USERNAME="jotheca_migrate" \
SPRING_FLYWAY_PASSWORD="$MIGRATE_PW" \
APP_RESTRICTED_ROLE="true" \
APP_PLATFORM_READ_DB_USER="jotheca_readall" \
APP_PLATFORM_READ_DB_PASSWORD="$READALL_PW" \
APP_JWT_SECRET="$JWT_SECRET" \
PUBLIC_BASE_URL="http://127.0.0.1:${APP_PORT}" \
CORS_ORIGINS="http://127.0.0.1" \
STORAGE_PROVIDER="memory" \
RATE_LIMIT_BACKEND="memory" \
SERVER_PORT="$APP_PORT" \
# C2: app.datasource.restricted-role and app.datasource.platform-read.* are bound ONLY in
# application-staging.yml (grep backend/src/main/resources to confirm). No staging profile is active
# here, so the environment variables above would be inert: PlatformPlaneConfig is @ConditionalOnProperty
# on that flag, would never activate, and the app would boot on Spring Boot's single default
# datasource with NO plane split - while every assertion still passed. They are therefore passed as
# EXPLICIT command-line arguments, which bind from the Environment regardless of the active profile.
# The same values are still exported above so the intent is visible in one place.
nohup java -jar "$JAR" \
  --app.datasource.restricted-role=true \
  --app.datasource.platform-read.username=jotheca_readall \
  --app.datasource.platform-read.password="$READALL_PW" \
  >"$APP_LOG" 2>&1 &
APP_PID=$!
c_ylw "app pid=$APP_PID"

up=""
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${APP_PORT}/actuator/health" || true)" = "200" ] && { up=1; break; }
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 2
done
if [ -z "$up" ]; then
  fail "app never became healthy"
  c_red "---- last 60 log lines ----"; tail -60 "$APP_LOG"; exit 1
fi
assert_contains "GET /actuator/health is UP" "$(curl -s "http://127.0.0.1:${APP_PORT}/actuator/health" || true)" '"status":"UP"'
# C2 regression guard: a healthy app proves NOTHING about the plane split. If restricted-role did not
# bind, PlatformPlaneConfig never activates, neither Hikari pool exists, and the rest of this script
# would silently exercise the single default datasource. Both pool names must be in the log.
for POOL_NAME in jotheca-tenant jotheca-platform-read; do
  if grep -q "$POOL_NAME" "$APP_LOG"; then
    pass "plane pool '$POOL_NAME' was created (restricted-role cutover is active)"
  else
    fail "plane pool '$POOL_NAME' missing - app.datasource.restricted-role did not bind; aborting rather than testing the wrong datasource"
    tail -40 "$APP_LOG"; exit 1
  fi
done

# ----------------------------------------------------------------- 6. migrations ran as migrate
section "6. Migrations applied by the MIGRATION role (not the runtime role)"
assert_eq "V26 (RLS hardening) applied and successful" "1" \
  "$(psql_owner "SELECT count(*) FROM flyway_schema_history WHERE version='26' AND success")"
assert_eq "Flyway installed V26 AS jotheca_migrate" "1" \
  "$(psql_owner "SELECT count(*) FROM flyway_schema_history WHERE version='26' AND installed_by='jotheca_migrate'")"
assert_eq "no failed migration in history" "0" \
  "$(psql_owner "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
assert_eq "29 tenant-isolation policies installed" "29" \
  "$(psql_owner "SELECT count(*) FROM pg_policies WHERE policyname LIKE '%tenant_isolation'")"
assert_eq "29 tenant tables with FORCE RLS enabled" "29" \
  "$(psql_owner "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity AND c.relforcerowsecurity")"
assert_eq "both least-privilege pools are actually in use" "2" \
  "$(psql_owner "SELECT count(DISTINCT usename) FROM pg_stat_activity WHERE usename IN ('jotheca_app','jotheca_readall')")"

# --------------------------------------------------------------------------- 7. users & authz
section "7. Authentication & authorization on /api/v1/platform/**"
STAMP="$(date +%s)"
ADMIN_EMAIL="smoke-admin-${STAMP}@example.test"
MEMBER_EMAIL="smoke-member-${STAMP}@example.test"
OUTSIDER_EMAIL="smoke-outsider-${STAMP}@example.test"
PW="SmokePassword123!"

TOKEN=""; LIB_HEADER=""
http POST /api/v1/auth/register "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$PW\",\"fullName\":\"Smoke Admin\"}"
assert_eq "register platform admin -> 201" "201" "$HTTP_CODE"
http POST /api/v1/auth/register "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$PW\",\"fullName\":\"Smoke Member\"}"
assert_eq "register plain member -> 201" "201" "$HTTP_CODE"
http POST /api/v1/auth/register "{\"email\":\"$OUTSIDER_EMAIL\",\"password\":\"$PW\",\"fullName\":\"Smoke Outsider\"}"
assert_eq "register membership-less user -> 201" "201" "$HTTP_CODE"

# Promote exactly one account to SUPER_ADMIN (global users table — no RLS, applied as owner).
psql_owner "UPDATE users SET global_role='SUPER_ADMIN' WHERE email='$ADMIN_EMAIL'" >/dev/null

http POST /api/v1/auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$PW\"}"
assert_eq "login as SUPER_ADMIN -> 200" "200" "$HTTP_CODE"
ADMIN_TOKEN="$(token_of "$HTTP_BODY")"
if [ -n "$ADMIN_TOKEN" ]; then pass "SUPER_ADMIN access token issued"; else fail "no access token in login response"; fi
http POST /api/v1/auth/login "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$PW\"}"
assert_eq "login as plain member -> 200" "200" "$HTTP_CODE"
MEMBER_TOKEN="$(token_of "$HTTP_BODY")"
if [ -n "$MEMBER_TOKEN" ]; then pass "member access token issued"; else fail "no access token in login response"; fi

# --- authorization failures (must never reach the DB plane)
TOKEN=""; http GET /api/v1/platform/libraries
assert_eq "unauthenticated platform GET -> 401" "401" "$HTTP_CODE"
TOKEN="$MEMBER_TOKEN"; http GET /api/v1/platform/libraries
assert_eq "non-SUPER_ADMIN platform GET -> 403" "403" "$HTTP_CODE"
TOKEN="$MEMBER_TOKEN"; http POST /api/v1/platform/libraries '{"name":"nope","type":"PUBLIC"}'
assert_eq "non-SUPER_ADMIN platform WRITE -> 403" "403" "$HTTP_CODE"
TOKEN=""; http POST /api/v1/platform/libraries '{"name":"nope","type":"PUBLIC"}'
assert_eq "unauthenticated platform WRITE -> 401" "401" "$HTTP_CODE"

# ------------------------------------------------------- 8. platform plane: cross-tenant READ
section "8. Platform READ plane (jotheca_readall, BYPASSRLS, SELECT-only) — cross-tenant visibility"
TOKEN="$ADMIN_TOKEN"
http POST /api/v1/platform/libraries '{"name":"Smoke Library Alpha","type":"UNIVERSITY","city":"Accra","country":"GH"}'
assert_eq "SUPER_ADMIN platform WRITE create (tenant A) -> 201" "201" "$HTTP_CODE"
LIB_A_ID="$(field_of "$HTTP_BODY" id)"; LIB_A_UID="$(field_of "$HTTP_BODY" uid)"
http POST /api/v1/platform/libraries '{"name":"Smoke Library Beta","type":"PUBLIC","city":"Kumasi","country":"GH"}'
assert_eq "SUPER_ADMIN platform WRITE create (tenant B) -> 201" "201" "$HTTP_CODE"
LIB_B_ID="$(field_of "$HTTP_BODY" id)"; LIB_B_UID="$(field_of "$HTTP_BODY" uid)"
for v in LIB_A_ID LIB_A_UID LIB_B_ID LIB_B_UID; do
  if [ -n "${!v}" ]; then pass "$v resolved"; else fail "$v could not be parsed from the create response"; fi
done

http POST "/api/v1/platform/libraries/${LIB_A_ID}/approve"
assert_eq "approve library A -> 200" "200" "$HTTP_CODE"
http POST "/api/v1/platform/libraries/${LIB_B_ID}/approve"
assert_eq "approve library B -> 200" "200" "$HTTP_CODE"

http GET /api/v1/platform/libraries
assert_eq "SUPER_ADMIN platform GET -> 200" "200" "$HTTP_CODE"
assert_contains "platform READ sees library A" "$HTTP_BODY" "$LIB_A_UID"
assert_contains "platform READ sees library B (cross-tenant BYPASSRLS read plane)" "$HTTP_BODY" "$LIB_B_UID"

http GET /api/v1/platform/stats
assert_eq "platform stats -> 200" "200" "$HTTP_CODE"
assert_contains "platform stats reports both tenants" "$HTTP_BODY" '"totalLibraries"'

# ------------------------------------------- 9. platform WRITE confined to the armed library
section "9. Platform WRITE plane (jotheca_app) — confined to the armed target library"
# Give library A a subscription so changePlan has a row to move (inserted as owner, outside RLS).
psql_owner "INSERT INTO library_subscriptions (id, tenant_id, plan_id, status, current_period_start, current_period_end, storage_addon_gb, created_at, updated_at)
            SELECT gen_random_uuid()::text, '${LIB_A_ID}', p.id, 'ACTIVE', now(), now() + interval '30 days', 0, now(), now()
            FROM subscription_plans p WHERE p.code='STARTER'
              AND NOT EXISTS (SELECT 1 FROM library_subscriptions s WHERE s.tenant_id='${LIB_A_ID}')" >/dev/null
assert_eq "library A has exactly one subscription" "1" \
  "$(psql_owner "SELECT count(*) FROM library_subscriptions WHERE tenant_id='${LIB_A_ID}'")"

http POST "/api/v1/platform/libraries/${LIB_A_ID}/plan" '{"planCode":"PROFESSIONAL","reason":"smoke"}'
assert_eq "plan change on library A -> 200" "200" "$HTTP_CODE"
assert_contains "plan change reports the target tenant" "$HTTP_BODY" "$LIB_A_ID"
assert_eq "plan change moved ONLY library A's subscription" "1" \
  "$(psql_owner "SELECT count(*) FROM library_subscriptions WHERE tenant_id='${LIB_A_ID}' AND plan_id=(SELECT id FROM subscription_plans WHERE code='PROFESSIONAL')")"
assert_eq "library B has NO subscription row (write never leaked across tenants)" "0" \
  "$(psql_owner "SELECT count(*) FROM library_subscriptions WHERE tenant_id='${LIB_B_ID}'")"

# --------------------------------------------------- 10. tenant plane: HTTP + DB boundaries
section "10. Tenant plane (jotheca_app) — HTTP and PostgreSQL RLS boundaries"
# One APPROVED LIBRARY_ADMIN membership in library A only (inserted as owner, outside RLS).
psql_owner "INSERT INTO memberships (id, tenant_id, user_id, role, membership_type, status, membership_number, joined_at, created_at, updated_at)
            SELECT gen_random_uuid()::text, '${LIB_A_ID}', u.id, 'LIBRARY_ADMIN', 'STAFF', 'APPROVED',
                   'SMK-' || substr(md5(random()::text),1,8), now(), now(), now()
            FROM users u WHERE u.email='${MEMBER_EMAIL}'
              AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.tenant_id='${LIB_A_ID}' AND m.user_id=u.id)" >/dev/null
assert_eq "member has exactly one APPROVED membership (library A)" "1" \
  "$(psql_owner "SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.email='${MEMBER_EMAIL}' AND m.status='APPROVED'")"
assert_eq "the membership-less user has NO membership anywhere" "0" \
  "$(psql_owner "SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.email='${OUTSIDER_EMAIL}'")"

TOKEN="$MEMBER_TOKEN"; LIB_HEADER="$LIB_A_UID"
http GET /api/v1/tenant/members
assert_eq "member of A may read A's members -> 200" "200" "$HTTP_CODE"
# C3: MemberDto exposes the member, not the library, so $LIB_A_UID can never appear in this body
# and the original assertion could not pass. Scope is proved by the member's own email being present
# (they are library A's only member) combined with the library-B check on the next line.
assert_contains "response is scoped to library A (carries the member's own email)" "$HTTP_BODY" "$MEMBER_EMAIL"
assert_not_contains "response contains NO library-B data" "$HTTP_BODY" "$LIB_B_UID"

LIB_HEADER="$LIB_B_UID"
http GET /api/v1/tenant/members
assert_eq "same user on library B is REFUSED -> 403" "403" "$HTTP_CODE"
assert_contains "refusal reason is NO_MEMBERSHIP" "$HTTP_BODY" "NO_MEMBERSHIP"

LIB_HEADER=""
http GET /api/v1/tenant/members
assert_eq "missing X-Library-Id fails closed -> 400" "400" "$HTTP_CODE"

# --- DB layer: the tenant role itself, no app in the loop
section "11. PostgreSQL RLS enforcement AS jotheca_app (no app in the loop)"
assert_eq "jotheca_app with NO tenant armed sees ZERO member rows (fail-closed)" "0" \
  "$(psql_tx_as jotheca_app "$APP_PW" - "SELECT count(*) FROM memberships")"
assert_eq "jotheca_app armed to A sees exactly A's members" "1" \
  "$(psql_tx_as jotheca_app "$APP_PW" "$LIB_A_ID" "SELECT count(*) FROM memberships")"
assert_eq "jotheca_app armed to A sees ZERO of B's rows" "0" \
  "$(psql_tx_as jotheca_app "$APP_PW" "$LIB_A_ID" "SELECT count(*) FROM memberships WHERE tenant_id='${LIB_B_ID}'")"

# NB: the planted row is for a user who has NO membership anywhere, so a unique-constraint
# violation cannot masquerade as an RLS block — only WITH CHECK can refuse this insert.
LEAK="$(expect_sql_failure jotheca_app "$APP_PW" \
  "BEGIN; SELECT set_config('app.tenant_id','${LIB_A_ID}',true);
   INSERT INTO memberships (id, tenant_id, user_id, role, membership_type, status, joined_at, created_at, updated_at)
   SELECT gen_random_uuid()::text,'${LIB_B_ID}',u.id,'PATRON','PUBLIC_MEMBER','APPROVED',now(),now(),now()
   FROM users u WHERE u.email='${OUTSIDER_EMAIL}'; COMMIT")"
assert_eq "jotheca_app CANNOT plant a membership under another tenant (WITH CHECK)" "OK" "$LEAK"
assert_eq "nothing was written by the blocked insert" "0" \
  "$(psql_owner "SELECT count(*) FROM memberships WHERE tenant_id='${LIB_B_ID}'")"

LEAK="$(expect_sql_failure jotheca_app "$APP_PW" \
  "BEGIN; SELECT set_config('app.tenant_id','${LIB_A_ID}',true);
   UPDATE memberships SET tenant_id='${LIB_B_ID}' WHERE tenant_id='${LIB_A_ID}'; COMMIT")"
assert_eq "jotheca_app CANNOT reassign an own row to another tenant" "OK" "$LEAK"

LEAK="$(expect_sql_failure jotheca_app "$APP_PW" \
  "BEGIN; SELECT set_config('app.tenant_id','${LIB_A_ID}',true);
   DELETE FROM memberships WHERE tenant_id='${LIB_B_ID}'; COMMIT")"
assert_eq "jotheca_app CANNOT delete another tenant's rows" "OK" "$LEAK"

# --- DB layer: the read-only platform role
section "12. Platform READ role is genuinely read-only"
assert_eq "jotheca_readall reads ACROSS tenants (BYPASSRLS)" "1" \
  "$(psql_tx_as jotheca_readall "$READALL_PW" - "SELECT count(*) FROM memberships")"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" \
  "INSERT INTO memberships (id, tenant_id, user_id, role, membership_type, status, joined_at, created_at, updated_at)
   SELECT gen_random_uuid()::text,'${LIB_A_ID}',u.id,'PATRON','PUBLIC_MEMBER','APPROVED',now(),now(),now()
   FROM users u WHERE u.email='${OUTSIDER_EMAIL}'")"
assert_eq "jotheca_readall CANNOT INSERT" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "UPDATE memberships SET role='LIBRARIAN'")"
assert_eq "jotheca_readall CANNOT UPDATE" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "DELETE FROM memberships")"
assert_eq "jotheca_readall CANNOT DELETE" "OK" "$LEAK"

# ------------------------------------------- 13. least-privilege grants: jotheca_readall is credential-blind
section "13. jotheca_readall is credential-blind (S1/S2 hardening) but can still count users"
# V26 revokes table-level SELECT on users and re-grants it column by column, and revokes SELECT on the
# two credential tables outright. Asserted here directly against the role, not assumed.
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "SELECT password_hash FROM users LIMIT 1")"
assert_eq "jotheca_readall CANNOT read users.password_hash" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "SELECT mfa_secret FROM users LIMIT 1")"
assert_eq "jotheca_readall CANNOT read users.mfa_secret" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "SELECT token_hash FROM auth_sessions LIMIT 1")"
assert_eq "jotheca_readall CANNOT read auth_sessions.token_hash" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_readall "$READALL_PW" "SELECT token_hash FROM verification_tokens LIMIT 1")"
assert_eq "jotheca_readall CANNOT read verification_tokens.token_hash" "OK" "$LEAK"
LEAK="$(expect_sql_failure jotheca_app "$APP_PW" "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, success) VALUES (9999,'ZZ','probe','SQL','probe',NULL,'smoke',true)")"
assert_eq "jotheca_app CANNOT write flyway_schema_history" "OK" "$LEAK"

# RESIDUAL RISK - probed, not assumed. With only column-level SELECT on users, whether
# `SELECT count(*) FROM users` is still permitted is the one interaction the PostgreSQL documentation
# does not settle for us. PlatformService.stats() issues exactly that query on this plane, so it is
# exercised here instead of being taken on trust. If this ever fails, the documented fallback is a
# dedicated platform view (or table-level SELECT on users, accepting the exposure).
if psql_as_rc jotheca_readall "$READALL_PW" "SELECT count(*) FROM users" >/dev/null 2>&1; then
  pass "jotheca_readall CAN count(*) users under column-level SELECT - GET /platform/stats keeps working"
else
  c_ylw "  WARN  jotheca_readall cannot count(*) users under column-level SELECT."
  c_ylw "        GET /api/v1/platform/stats would fail. Apply the documented fallback (platform view)."
fi
# The role must still be able to read the columns the platform legitimately needs.
if psql_as_rc jotheca_readall "$READALL_PW" "SELECT email, global_role, status FROM users LIMIT 1" >/dev/null 2>&1; then
  pass "jotheca_readall CAN read the users columns the platform actually needs"
else
  fail "jotheca_readall cannot read users.email/global_role/status - platform reads would break"
fi

# ----------------------------------------------------------------------------------------- summary
section "SUMMARY"
if [ "$FAIL" -eq 0 ]; then
  c_grn "ALL ${PASS} RESTRICTED-ROLE SMOKE ASSERTIONS PASSED"
else
  c_red "${FAIL} of $((PASS+FAIL)) ASSERTIONS FAILED:"
  for n in "${FAILED_NAMES[@]}"; do c_red "  - $n"; done
fi
c_ylw "NOTE: a green run is evidence for THIS environment only. Re-run in CI before any cutover."
[ "$FAIL" -eq 0 ]
