# Phase A — Database‑Level Tenant‑Isolation Remediation: Proposed Design (for review)

**Status:** PROPOSED — **not implemented, not committed, not pushed.** Presented for approval before any migration/config file is written.
**Branch:** `arena/a775b9a4-j-otheca` · **Audit:** `COMPREHENSIVE-SECURITY-AUDIT-2026-10-11.md`

---

## 1. Root cause being fixed
The app connects to PostgreSQL as `jotheca`, which is the **superuser and table owner** (docker‑compose `POSTGRES_USER=jotheca`; Flyway and the runtime datasource share it). PostgreSQL **bypasses RLS for superusers/owners**, so the RLS backstop never filters app queries. Additionally every policy is **fail‑open** (`app.tenant_id IS NULL OR '' → all rows`) and has **no `WITH CHECK`**.

**Goal:** a dedicated least‑privilege runtime role that RLS actually applies to, with fail‑closed policies enforcing tenant ownership on read **and** write, while keeping migrations and the legitimate platform plane working and preserving app behavior.

---

## 2. Role model (three distinct roles — migration ≠ tenant‑runtime ≠ platform)

| Role | Superuser? | Owner? | BYPASSRLS? | Used by | Privileges |
|---|---|---|---|---|---|
| `jotheca_migrate` | no | **yes** (owns tables) | no | **Flyway only** | DDL, `GRANT`, `CREATE POLICY`; not used at app runtime |
| `jotheca_app` | no | no | **no (`NOBYPASSRLS`)** | **all `/api/v1/tenant/**`, auth, member flows** | `SELECT/INSERT/UPDATE/DELETE` on tenant tables; **subject to RLS** |
| `jotheca_readall` | no | no | **yes** (narrow) | **`/api/v1/platform/**` only (SUPER_ADMIN)** | read‑only across tenants; **every call audited** |

Why this satisfies the brief: migration/admin privilege is separate from runtime (#2); the tenant role cannot bypass RLS (#1); the platform cross‑tenant role is **explicit, narrowly scoped to `/platform/**`, and audited** — it is *not* an ordinary patron/staff role and is never used for tenant traffic.

> **DECISION‑1 (needs your call):** platform plane via a dedicated `BYPASSRLS` read role (above — simplest, audited) **vs.** keeping one role and using a server‑set GUC `app.is_platform='true'` that only the policy for platform‑nullable tables (e.g. `audit_logs`) honors (more granular, more moving parts). **I recommend the dedicated `jotheca_readall` role.**

---

## 3. Credential provisioning & Flyway wiring (no secrets in migrations or VCS)

- **Role creation & passwords are provisioned by the deploy/operator, never hardcoded.** A one‑time idempotent provisioning step (Compose `postgres` init or an ops runbook) runs `CREATE ROLE … PASSWORD :pw` with the password supplied from the environment/secret store at deploy time.
- **Flyway migrations contain only DDL/`GRANT`/policy statements — no passwords.** Flyway authenticates as `jotheca_migrate` via `SPRING_FLYWAY_USERNAME` / `SPRING_FLYWAY_PASSWORD` (env/secret), which Spring Boot reads independently of `spring.datasource.*`.
- **Runtime datasource** uses `jotheca_app` (`SPRING_DATASOURCE_USERNAME/PASSWORD`).
- `application.yml` gains `spring.flyway.user`/`password` (env‑templated, default empty → falls back to datasource user for local H2 dev).

---

## 4. Exact proposed migration (`V26__rls_hardening.java`, PostgreSQL‑only, no‑op on H2)

Idempotent, additive, **non‑destructive** (no table/data drops):

```sql
-- (a) grants for the least-privilege runtime role (role itself created by provisioning)
GRANT USAGE ON SCHEMA public TO jotheca_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jotheca_app;
-- (platform read role)
GRANT USAGE ON SCHEMA public TO jotheca_readall;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO jotheca_readall;

-- (b) fail-closed + WITH CHECK + FORCE, per tenant-owned table T in [inventory §6]:
ALTER TABLE T ENABLE ROW LEVEL SECURITY;
ALTER TABLE T FORCE ROW LEVEL SECURITY;                 -- see DECISION-2
DROP POLICY IF EXISTS T_tenant_isolation ON T;
CREATE POLICY T_tenant_isolation ON T
  USING      (tenant_id = current_setting('app.tenant_id', true))   -- unset GUC -> NULL -> 0 rows (fail-closed)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));  -- blocks cross-tenant INSERT/UPDATE
```

**Special case — `audit_logs`** (`tenant_id` is NULLABLE for platform‑plane events): its `USING` additionally allows `tenant_id IS NULL AND current_setting('app.is_platform', true) = 'true'`; `WITH CHECK` stays tenant‑bound so the tenant plane cannot forge platform rows. (If DECISION‑1 = dedicated role, platform audit reads go through `jotheca_readall` instead and this GUC branch is unnecessary.)

> **DECISION‑2 (needs your call):** apply **`FORCE ROW LEVEL SECURITY`**? Recommended **yes** (owner/migrate role is then also bound for DML, closing the owner gap). Safe because Flyway DDL isn't DML‑filtered and `DevDataSeeder` is dev/H2‑only. If you run any cross‑tenant backfill as the owner in prod, we scope FORCE off for that step.

---

## 5. Preserving the platform plane & seeder
- **Platform plane** (`/api/v1/platform/**`, SUPER_ADMIN): routed to `jotheca_readall` (DECISION‑1). Implemented by keying the datasource on the resolved plane (the URL spaces are already disjoint) — an `AbstractRoutingDataSource` or a dedicated secondary `DataSource` for platform services. Every platform read is audited (existing `AuditService`).
- **`DevDataSeeder`**: dev/H2‑only; RLS is a no‑op there, so it is unaffected. Confirm it never runs against real Postgres as `jotheca_app` in staging/prod (it should be profile‑gated).

---

## 6. Tenant‑table inventory (requirement #4)

**Tenant‑owned (29 — all get the hardened policy):** `memberships, invitations, audit_logs*, categories, branches, shelf_locations, books, book_copies, loan_policies, loans, reservations, fines, notifications, book_interests, purchase_requests, media_objects, book_ratings, book_reviews, review_reports, review_helpful_votes, storage_usage, library_subscriptions, payments, book_embeddings, ai_usage_counters, search_events, issues, issue_messages, staff_permissions`. (`*audit_logs` = nullable special case.)

**Not tenant‑owned (correctly excluded — investigated):**
- `libraries` — the tenant root itself.
- **`author_profiles` — has `user_id`, not `tenant_id` → user‑global; NOT a gap.**
- `users, auth_sessions, verification_tokens` — global identity/session/token tables (user‑keyed).
- `subscription_plans` — global plan catalog.
- `library_onboarding_requests` — platform‑plane.

---

## 7. PostgreSQL integration tests (requirement #5) — Testcontainers, connect as `jotheca_app`

New `RlsTenantIsolationTest` (runs in CI with `pgvector/pgvector:pg16`; **cannot run in this sandbox — no Docker/Postgres, so NOT VERIFIED here**):
1. **Cross‑tenant read denied** — as `jotheca_app` with `app.tenant_id=A`, `SELECT` returns 0 of Library B's rows.
2. **Cross‑tenant write denied** — `INSERT`/`UPDATE` with a foreign `tenant_id` rejected by `WITH CHECK`.
3. **Missing tenant context** — GUC unset → **0 rows**, not all rows (proves fail‑closed).
4. **Unauthorized reassignment** — `UPDATE … SET tenant_id=<other>` blocked.
5. **Connection‑pool leakage** — return the connection to the pool; the next borrower **without** setting the GUC sees 0 rows (`SET LOCAL` does not persist).
6. **Platform plane** — `jotheca_readall` can read across tenants (authorized scope) and the access is audited; `jotheca_app` cannot.

---

## 8. Deployment: Docker Compose & production
- **Compose:** `postgres` provisions three roles from `.env` (`JOTHECA_MIGRATE_PW`, `JOTHECA_APP_PW`, `JOTHECA_READALL_PW` — never committed). Backend env: `SPRING_FLYWAY_USERNAME=jotheca_migrate`, `SPRING_DATASOURCE_USERNAME=jotheca_app`, plus a platform‑plane datasource user `jotheca_readall`. `.env.staging.example` updated with the (empty) placeholders.
- **Production:** same three credentials injected from the secret manager; Flyway job runs as `jotheca_migrate`; app pods run as `jotheca_app`; platform services as `jotheca_readall`. No passwords in images, migrations, or git.
- **Backup/rollout/rollback:** `pg_dump` before apply; rollout order = provision roles → run `V26` → repoint runtime datasource → smoke‑test a cross‑tenant denial. **Rollback** = repoint datasource back to the owner (RLS bypassed → prior behavior) and/or restore the previous policy set; **no tenant data is dropped** (policies are replaced, not tables).

---

## 9. Implemented vs executed (requirement #8)
- **This document implements nothing.** On approval I will add: `V26__rls_hardening.java`, the role‑provisioning step, `application*.yml` + Compose/env wiring, and `RlsTenantIsolationTest`.
- **Executed successfully here:** none of the above (no Docker/Postgres/JVM). **Everything runtime remains NOT VERIFIED until CI runs the Testcontainers suite and you run `mvn clean verify`.**

---

## 10. Deployment risks
1. **Platform plane breakage** if plane‑routing or `jotheca_readall` is mis‑scoped → mitigate with the audit + a pre‑prod cross‑tenant test before cutover.
2. **App queries that rely on cross‑tenant visibility** would start returning empty under fail‑closed RLS → the new tests + smoke tests catch these before rollout.
3. **FORCE** could interfere with any owner‑run cross‑tenant DML → scope off for that step if needed (DECISION‑2).
4. **Sequencer/`USAGE` grants** if any table later uses DB sequences → migration grants `USAGE` on all sequences defensively.

---

## Phase B (separate, isolated from DB roles) — spoofable XFF + unbounded rate‑limit keys
- **R2 (`RateLimitFilter`):** trust `X-Forwarded-For` **only from configured trusted proxies** (allowlist via `server.forward-headers-strategy` / a `TRUSTED_PROXIES` property); otherwise use `getRemoteAddr()`. Regression test: spoofed XFF from an untrusted source does not change the limit key.
- **R3 (`InMemoryRateLimiter`):** bound the map — TTL expiry of idle keys and/or a max size with eviction; unit test asserts the map stays bounded. (Redis limiter already exists for scaled deploys.)

Both Phase B changes are confined to `identity/security/*` and carry no DB‑role impact.

---

## What I need from you to proceed to implementation
1. **DECISION‑1:** platform plane = dedicated `jotheca_readall` (BYPASSRLS, audited) — recommended — or a policy GUC?
2. **DECISION‑2:** enable **`FORCE ROW LEVEL SECURITY`** (recommended yes)?
3. Confirm role names (`jotheca_migrate` / `jotheca_app` / `jotheca_readall`) and that migrations keep the owner role while runtime uses `jotheca_app`.

On your confirmation I will implement Phase A (+ Phase B), static‑check, and present the concrete diffs — still **without committing or pushing** until you approve those.

---

## Implementation status (2026-10-11) — IMPLEMENTED, NOT YET EXECUTED

Everything below is written and **static-checked only** (YAML/XML/bash-syntax/brace-balance). It has
**NOT been compiled or run** in this sandbox (no javac/Maven/Docker/Postgres). The RLS integration test
executes in CI. Nothing is committed or pushed — awaiting review.

### Implemented (this change set)
- **`V26__RlsHardening.java`** (new Flyway migration, PostgreSQL-only, no-op on H2): over all 29 tenant
  tables → `ENABLE` + `FORCE ROW LEVEL SECURITY`, drop+recreate `*_tenant_isolation` with fail-closed
  `USING`+`WITH CHECK` (`tenant_id = current_setting('app.tenant_id', true)`). **`audit_logs`** (nullable
  `tenant_id`) gets a NULL-aware variant that still never leaks another tenant's rows and blocks planting
  a tenant row while unarmed. Least-privilege `GRANT`s to `jotheca_app`/`jotheca_readall`, tolerant of a
  not-yet-provisioned role (skip on SQLState 42704/42501). No secrets, non-destructive, idempotent.
- **`RlsTenantIsolationTest.java`** (new, Testcontainers `pgvector/pgvector:pg16`, connects AS
  `jotheca_app`/`jotheca_readall`): cross-tenant read denial, missing-context fail-closed, own-tenant
  write allowed, cross-tenant write + unauthorized reassignment blocked (WITH CHECK), connection-pool
  leakage (SET LOCAL cleared at commit), platform role read-all-but-SELECT-only, and audit_logs nullable
  semantics. Added `org.testcontainers:junit-jupiter` + `:postgresql` (test scope, version-managed).
- **`infra/db/provision-roles.sh`** (new, idempotent, secrets from env only): creates `jotheca_migrate`
  (NOSUPERUSER/NOBYPASSRLS), `jotheca_app` (NOSUPERUSER/NOBYPASSRLS), `jotheca_readall` (NOSUPERUSER/
  BYPASSRLS, SELECT-only) with passwords passed as psql variables — never in the file or VCS.
- **Compose/env**: postgres mounts the provisioning script into `initdb.d`; backend gains
  `SPRING_FLYWAY_*` + `APP_RUNTIME_DB_*` + `APP_RESTRICTED_ROLE` + `APP_RATE_LIMIT_TRUSTED_PROXIES`.
  **All default to today's owner-role behavior** (restricted-role=false) — opt-in cutover.
- **`application-staging.yml`**: Flyway user/password templated (default = datasource creds ⇒ unchanged);
  `app.datasource.restricted-role` (default false) + `app.rate-limit.trusted-proxies`.
- **Phase B (separate from DB roles)**: `RateLimitFilter` trusts `X-Forwarded-For` only from configured
  trusted proxies (default: ignore header, use socket peer); `InMemoryRateLimiter` evicts idle keys
  (bounded memory).

### NOT implemented here — presented as proposal (needs review + CI before wiring)
- **Platform-plane routing**: a second `DataSource` (`jotheca_readall`, BYPASSRLS, read-only, audited)
  for `/api/v1/platform/**`. Not wired — enabling `restricted-role=true` without it would break the
  platform plane (0 rows). Ship routing + runtime-role switch together.
- **Runtime cutover** (`APP_RESTRICTED_ROLE=true`, `SPRING_DATASOURCE_USERNAME=jotheca_app`).

### Open items / risks
- Flyway-as-`jotheca_migrate` (distinct non-superuser owner) needs a one-time object-ownership transfer on
  existing DBs; until then Flyway stays the owner (`jotheca`). Documented, out of the non-destructive scope.
- New tenant tables in future migrations MUST add their own RLS policy + grant (process rule).
- `TenantAwareDataSource` builds the GUC via string concat (`SET LOCAL app.tenant_id='<id>'`); the id is
  server-resolved (low risk) — recommend `set_config('app.tenant_id', ?, true)` bind param as hardening.
- RLS test requires Docker + Docker Hub in CI. Nothing verified at runtime in this environment.

---

## UPDATE — option (b): platform-plane routing implemented + gated (2026-10-11)

Platform-plane routing is now IMPLEMENTED behind the existing `app.datasource.restricted-role` flag
(default **false** ⇒ whole feature inert, today's single owner-role datasource unchanged). Cutover is
opt-in and requires CI validation. Static-checked only (see toolchain evidence below).

### Roles (now four, kept separate)
- `jotheca_app` — tenant plane (NOBYPASSRLS, RLS-confined). Never gains read-all.
- `jotheca_readall` — platform READS (BYPASSRLS, **SELECT-only**). GET `/api/v1/platform/**` only.
- `jotheca_platform` — platform WRITES (BYPASSRLS, DML). Non-GET `/api/v1/platform/**` only. Kept separate
  from `jotheca_app` so the tenant plane never acquires cross-tenant write.
- `jotheca_migrate` — Flyway/DDL.

### Routing (new, gated)
- `PlaneContext` (ThreadLocal plane) + `PlaneRoutingDataSource` (`AbstractRoutingDataSource`, `@Primary`)
  + `PlaneRoutingInterceptor` (runs AFTER Spring Security; GET platform→read role, other→write role, else
  tenant; audits `PLATFORM_ACCESS` via the write role since the read role is read-only; clears plane).
- `PlatformPlaneConfig` (`@ConditionalOnProperty restricted-role=true`) builds 3 least-privilege Hikari
  pools + the routing datasource; default target = tenant (fail-safe: an unset plane is never platform).
- Authorization of `/api/v1/platform/**` stays in `SecurityConfig` (`hasRole("SUPER_ADMIN")`) — unchanged.

### Correctness fixes applied this pass
- **`payments` tenant_id is NULLABLE** (added by V14) ⇒ NULL-aware fail-closed policy now covers BOTH
  `audit_logs` and `payments` (inventory re-verified: exactly 29 tenant tables, all RLS-covered, `author_profiles`
  correctly excluded as user-global).
- **V26 grants sequence USAGE** (was missing ⇒ serial/identity inserts would have failed under the cutover).
- **V26 grant handling (req):** skip ONLY on SQLState 42704 (role not provisioned, genuinely optional);
  ANY other failure — notably 42501 — now FAILS the migration (never silently ignored).
- **`TenantAwareDataSource`** now arms the GUC with a parameterized `set_config('app.tenant_id', ?, true)`
  (bind param; no string-concat injection surface).

### Tests (written; NOT executed — no toolchain here)
- `RlsTenantIsolationTest` (Testcontainers, AS jotheca_app/readall): cross-tenant read/write denial,
  reassignment block, missing-context fail-closed, pool leakage, read-only platform role, audit/payments
  nullable semantics.
- `PlaneRoutingTest` (pure unit): plane decision (GET→read, POST→write, else tenant), audit-on-platform +
  plane clearing, routing lookup key.

### Toolchain evidence (req: report exactly what ran)
`java: command not found`; no `mvnw`, no `mvn`, no `javac`. ⇒ **No compile, no test run, no Testcontainers
execution in this sandbox.** All verification was static (YAML/XML parse, `bash -n`, brace/paren balance).
Nothing here should be treated as runtime-verified until CI runs `mvn verify` (Docker required for
Testcontainers) with `APP_RESTRICTED_ROLE=true`.

---

## UPDATE 2 — point‑by‑point resolution (2026‑10‑11)

### Point 1 — jotheca_platform BYPASSRLS: ELIMINATED (least privilege achieved)
Determination: BYPASSRLS was **not** required. Every platform write targets a single library
(`create`/`approve`/`suspend`/`reject` touch only global tables + NULL‑tenant audit; `changePlan` targets a
known library; onboarding‑approve targets the newly‑created library). So the dedicated BYPASSRLS write role
was **removed**. Platform **writes now run as `jotheca_app`** (RLS‑confined) with the target library's tenant
context armed via `PlatformWriteContext`:
- `changePlan` → `beginForKnownLibrary(id)` before the `@Transactional` service (GUC armed at tx start).
- onboarding `approve` → `armForNewLibrary(id)` after the library row is created (re‑arms the tx‑local GUC).
- `create`/`approve`/`suspend`/`reject` → global‑only, no context needed.
Only platform **GETs** use `jotheca_readall` (BYPASSRLS, SELECT‑only). `jotheca_app` never gains read‑all;
there is no cross‑tenant write role. Test `platformWriteRoleConfinedToArmedLibrary` proves the write role
cannot touch another tenant.

### Point 2 — interceptor ordering + cleanup
`PlaneRoutingInterceptor` is a Spring MVC `HandlerInterceptor`, which runs in the DispatcherServlet **after**
the Spring Security filter chain — so it only executes for already‑authenticated (and, for
`/api/v1/platform/**`, SUPER_ADMIN‑authorized) requests. `afterCompletion` clears **both** `PlaneContext` and
`TenantContext` for platform requests, and runs on success, on exception, and after view rendering (covered
by `planeClearedOnException`). All platform controllers are **synchronous** (return DTOs, no
`DeferredResult`/reactive), so there is no async‑completion gap; if async handlers are added later, cleanup
must move to the async callback. DB‑connection behavior is exercised by `RlsTenantIsolationTest` (real
connections as each role), not only by routing decisions.

### Points 3, 4, 6 — NOT EXECUTED HERE (no toolchain)
This sandbox has **no `java`/`javac`/`mvn`/`mvnw`, no Docker, no Postgres** (`java: command not found`). So I
could **not** run a boot smoke test, could **not** connect to a real database, could **not** verify FORCE RLS
with live roles, and could **not** run CI. **No test has executed; nothing is runtime‑verified; I make no
claim that any Testcontainers/unit test passed.** The exact CI commands to run are below.

### Point 5 — 29‑table inventory + nullable policies (verified statically against the schema DDL)
Re‑derived from all migrations incl. `ALTER TABLE ... ADD COLUMN tenant_id`: exactly **29** tenant tables,
all RLS‑covered by V2..V24; **nullable** `tenant_id` on **`audit_logs`** and **`payments`** (both get the
NULL‑aware fail‑closed predicate that permits a NULL‑tenant row only when no tenant is armed); `author_profiles`
correctly excluded (user‑global). Not re‑checked against a live database (none available).

### CI commands to run (Docker required) before any cutover
```
cd backend
mvn -q test                                   # unit tests incl. PlaneRoutingTest (no Docker)
mvn -q verify                                 # full build + RlsTenantIsolationTest (Testcontainers/Docker)
# boot smoke test with restricted role:
SPRING_PROFILES_ACTIVE=staging APP_RESTRICTED_ROLE=true \
  SPRING_DATASOURCE_USERNAME=jotheca_app SPRING_DATASOURCE_PASSWORD=<app_pw> \
  SPRING_FLYWAY_USERNAME=jotheca_migrate SPRING_FLYWAY_PASSWORD=<migrate_pw> \
  APP_PLATFORM_READ_DB_PASSWORD=<readall_pw> \
  mvn -q spring-boot:run
# then exercise: tenant request (own rows only), platform GET (cross-tenant), platform write
# (create/plan/approve — confined to target library), and a non-SUPER_ADMIN platform call (403).
```

---

## UPDATE 3 — final review-only pass (2026-10-11) — trace findings + correction

### Correction made this pass
`PlatformWriteContext.armForNewLibrary` previously used `DataSourceUtils.getConnection(dataSource)` to run
`set_config`. Under `JpaTransactionManager` that is **not guaranteed** to be the same JDBC connection
Hibernate uses for the subsequent `memberships`/`subscription` inserts, so the GUC could be armed on the
wrong connection and the tenant-scoped inserts would fail `WITH CHECK`. **Fixed:** the GUC is now armed via
the transaction-scoped JPA `EntityManager` (`createNativeQuery("SELECT set_config('app.tenant_id', :tenantId, true)")`),
which is exactly the connection the following inserts use. `beginForKnownLibrary` (changePlan) is unchanged —
it only sets `TenantContext` before the `@Transactional` boundary, so `TenantAwareDataSource` arms the GUC at
tx start on the correct connection.

### Point 1 — trace (PlatformWriteContext / GUC / leakage)
- `beginForKnownLibrary` sets `TenantContext` BEFORE the `@Transactional` service; `TenantAwareDataSource`
  arms the GUC on the connection Hibernate opens for that tx (setAutoCommit(false) hook) — same connection.
- `armForNewLibrary` now arms via the `EntityManager` (same tx connection as the writes) — verified above.
- Leakage: `PlaneContext` cleared in the interceptor `afterCompletion` `finally`; `TenantContext` cleared by
  `TenantResolutionFilter` `finally` (tenant requests) AND by the interceptor before the platform audit
  (platform requests). No request/connection can inherit another's context.

### Point 2 — trace (onboarding approve)
`@Transactional approve`: createByPlatform (global `libraries`) + approve (global) → `armForNewLibrary(newId)`
→ `memberships.save` (tenant=newId, GUC=newId ✓) → `libraries.save` (global) → `subscriptions.save`
(tenant=newId ✓) → `requests.save` (global) → audits. Context is armed AFTER the library id exists and
BEFORE the first tenant-scoped insert, on the same connection. Single transaction → atomic rollback preserved
(the mid-tx rearm does not open a nested tx). `audit.log` is REQUIRES_NEW (unchanged; survives rollback).

### Point 3 — trace (changePlan)
`PlatformController.changePlan` → `beginForKnownLibrary(id)` (sets `TenantContext`) → `platformService.changePlan`
`@Transactional` → tx start arms GUC=id → all scoped reads/writes (`subscriptions`, `memberships`, `books`,
`branches`) confined to id. Context set before the transaction's tenant-scoped SQL executes. ✓

### Point 4 — V26 vs full history
29 tenant tables each get ENABLE+FORCE RLS + fail-closed USING+WITH CHECK + grants (incl. sequence USAGE);
nullable `audit_logs`/`payments` get the NULL-aware predicate. Grants: 42704 (role absent) → skip+warn;
ANY other state (notably 42501) → migration FAILS. On the secured path (`restricted-role=true`) a missing
role ⇒ the app cannot connect ⇒ startup fails ⇒ a partially-secured deployment is never treated as successful.

### Point 5 — jotheca_readall
Used only for GET `/api/v1/platform/**` (interceptor). Its credentials live only in the `platformReadDataSource`
pool; tenant/write paths use `jotheca_app`. Platform audit writes go through `jotheca_app` (TENANT plane,
tenant=NULL, permitted by the NULL-aware policy) and are logged via `log.error` on failure (never silent).

### Point 6 — CI checklist (ALL UNEXECUTED HERE — no java/mvn/Docker/PG in this sandbox)
Prereqs: Docker + Docker Hub (pull pgvector/pgvector:pg16); JDK 21; Maven (or ./mvnw).
```
cd backend
mvn -q -DskipTests compile                      # 1. compilation
mvn -q test                                      # 2. unit tests (PlaneRoutingTest; no Docker)
mvn -q verify -Djwt.test.skip=false             # 3. full suite + RlsTenantIsolationTest (Testcontainers/Docker)
# 4. boot smoke test with restricted role + separate Flyway creds:
SPRING_PROFILES_ACTIVE=staging APP_RESTRICTED_ROLE=true \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5532/jotheca \
  SPRING_DATASOURCE_USERNAME=jotheca_app SPRING_DATASOURCE_PASSWORD=$JOTHECA_APP_PW \
  SPRING_FLYWAY_USERNAME=jotheca_migrate SPRING_FLYWAY_PASSWORD=$JOTHECA_MIGRATE_PW \
  APP_PLATFORM_READ_DB_PASSWORD=$JOTHECA_READALL_PW \
  APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1 \
  mvn -q spring-boot:run
# then assert: migrations applied as jotheca_migrate; tenant request sees only its rows;
# platform GET (SUPER_ADMIN) reads cross-tenant; platform write (create/plan/approve) confined to target;
# non-SUPER_ADMIN platform call -> 403; a cross-tenant write -> 0 rows / blocked.
```
**Status: nothing above has been run. No test is claimed to pass until CI reports results.**

---

## UPDATE 4 — executable smoke test + CI gating + explicit prerequisites (2026-10-11)

This update answers the review conditions directly: an **executable** restricted-role smoke test that
checks real HTTP responses **and** real tenant boundaries (not just "Spring Boot started"), a CI gate
that **fails** if Testcontainers or the RLS tests are skipped, and an explicit statement of the
database-provisioning prerequisites.

### Execution status — READ THIS FIRST

| Artifact | Status |
|---|---|
| `scripts/restricted-role-smoke.sh` | **WRITTEN, NOT EXECUTED.** Logic-validated against a mocked Docker/curl/Java harness in the authoring sandbox (67/67 assertions pass on a "good" environment, 7 fail on a deliberately fail-open one). **No real Postgres, no real JVM, no real Docker existed here** — the run is UNVERIFIED. |
| `scripts/ci-rls-gate.sh` | **WRITTEN, NOT EXECUTED IN CI.** Logic-validated against 9 synthetic surefire-XML fixtures (healthy / missing report / zero tests / skipped / failed / other-suite-skipped / no docker / daemon-down / no reports dir). Behaviour is correct in all 9. Not yet run by a real pipeline. |
| `RlsTenantIsolationTest` | **WRITTEN, NOT EXECUTED.** No Testcontainers/Docker in the authoring sandbox. |
| `PlaneRoutingTest` | **WRITTEN, NOT EXECUTED.** Pure unit test; no compile has happened (no javac/Maven). |
| `V26__RlsHardening` | **WRITTEN, NOT EXECUTED.** Never applied to a real Postgres here. |
| Everything else in Phase A | Static checks only (YAML/XML parse, `bash -n`, brace/paren balance). |

**No test in this change set is claimed to pass. Nothing has been compiled, booted, or run against a
real database.** The authoring sandbox has no `java`, no `javac`, no `mvn`/`mvnw`, no `docker` and no
`psql` (`docker: command not found`, `psql: command not found`). All runtime and integration
assertions below are therefore labelled **UNVERIFIED** until CI reports results.

### Database-provisioning prerequisites (explicit)

The restricted-role cutover needs the three least-privilege roles to exist in Postgres **before** the
app boots with `app.datasource.restricted-role=true`. They are created by
`infra/db/provision-roles.sh` — **never** by a migration, and **never** with a password in source.

1. **Fresh database (docker-compose / CI)** — nothing to do: compose mounts the script into
   `/docker-entrypoint-initdb.d/`, and the smoke test runs it explicitly.
2. **Existing database** — run it once, as a principal that may create roles:
   ```bash
   JOTHECA_MIGRATE_PW=... JOTHECA_APP_PW=... JOTHECA_READALL_PW=... \
   PGHOST=... PGUSER=jotheca PGDATABASE=jotheca bash infra/db/provision-roles.sh
   ```
3. **Flyway principal** — on a fresh DB set `POSTGRES_USER=jotheca_migrate` so it owns the schema. On
   an existing DB either transfer object ownership (`REASSIGN OWNED`) or keep Flyway on the current
   owner. **V26 grants fail the migration on SQLState 42501** rather than silently continuing, and
   `restricted-role=true` with a missing role makes the app fail to connect ⇒ startup fails. A
   partially-secured deployment is never treated as successful.
4. **Secrets** — `JOTHECA_MIGRATE_PW` / `JOTHECA_APP_PW` / `JOTHECA_READALL_PW` (and
   `APP_PLATFORM_READ_DB_PASSWORD`, `APP_RUNTIME_DB_PASSWORD`, `APP_FLYWAY_DB_PASSWORD`) come from
   the environment / secret manager only. Compose defaults are DEV-ONLY placeholders.
5. **Toolchain for the smoke test** — Docker with a reachable daemon, Docker Hub access to pull
   `pgvector/pgvector:pg16`, JDK 21, Maven (or `backend/mvnw`), `curl`, bash 4+. The script fails
   closed if any is missing; it never degrades into a weaker check.

### `scripts/restricted-role-smoke.sh` — what it actually proves

67 assertions across 13 sections, against a real `pgvector/pgvector:pg16` and a real booted app
(`restricted-role=true`, runtime = `jotheca_app`, Flyway = `jotheca_migrate`, platform read =
`jotheca_readall`):

* **0–4** prerequisites (fails closed), real Postgres, role provisioning, role attributes —
  `jotheca_app`/`jotheca_migrate` are `NOSUPERUSER` + `NOBYPASSRLS`, `jotheca_readall` is
  `NOSUPERUSER` + `BYPASSRLS`, and **no `jotheca_platform` role exists**.
* **5–6** boot, then migrations: V26 applied **as `jotheca_migrate`** (separate Flyway credentials),
  29 policies, 29 tables with FORCE RLS, and **both least-privilege pools actually in use**.
* **7** authorization: unauthenticated → **401**, non-SUPER_ADMIN → **403** for both GET and POST on
  `/api/v1/platform/**` (SecurityConfig; the plane is never reached).
* **8** platform READ plane: SUPER_ADMIN GET → **200** and sees **both** tenants (cross-tenant
  `jotheca_readall`, BYPASSRLS, SELECT-only).
* **9** platform WRITE plane: SUPER_ADMIN create/approve → **201/200**, plan change → **200**, and the
  subscription moved **only** the target library's row — the second tenant keeps none.
* **10** tenant plane over HTTP: member of A reads A (**200**, no B data); the same user on B → **403
  NO_MEMBERSHIP**; missing `X-Library-Id` → **400**.
* **11** PostgreSQL RLS **as `jotheca_app` with no app in the loop**: no tenant armed → **0 rows**;
  armed to A → only A's rows; planting a row under B → **blocked** (WITH CHECK); reassigning an own
  row to B → **blocked**; deleting B's rows → **blocked**. The planted row deliberately uses a user
  with no memberships so a unique-constraint error cannot masquerade as an RLS block.
* **12** `jotheca_readall` reads across tenants but **cannot INSERT/UPDATE/DELETE**.

Negative-control evidence (mocked): with the RLS/read-only enforcement removed, the script reports
**7 failures and exits 1** — it detects a security regression rather than rubber-stamping a boot.

### `scripts/ci-rls-gate.sh` + CI wiring

`mvn verify` exits 0 when a test is **skipped**, and Testcontainers silently no-ops when Docker is
unavailable — either way the RLS proof would be *absent* from CI while everything looked green. The
gate turns that into a hard failure. `.github/workflows/ci.yml` now has:

* a `GATE — the PostgreSQL RLS / Testcontainers suite actually ran and was not skipped` step in
  `backend`, running `../scripts/ci-rls-gate.sh`;
* a new `restricted-role-smoke` job running `scripts/restricted-role-smoke.sh`.

The gate asserts: Docker reachable → `TEST-com.jotheca.tenancy.RlsTenantIsolationTest.xml` exists →
`tests > 0` → `skipped == 0` → `failures == 0 and errors == 0` → **and** no other suite in the run was
skipped (the suite contains no `@Disabled`/assumption-based tests, so a skip always means the
environment failed).

### CI commands to run before any cutover

```bash
# 1. compile + unit tests (PlaneRoutingTest; no Docker needed)
cd backend && mvn -q -DskipTests compile && mvn -q test
# 2. full suite incl. RlsTenantIsolationTest (Testcontainers — Docker required), then the gate
cd backend && mvn -B verify && ../scripts/ci-rls-gate.sh
# 3. end-to-end restricted-role proof (starts its own Postgres; fails closed on any missing prereq)
scripts/restricted-role-smoke.sh
```

Prerequisites for the above: Docker + Docker Hub (`pgvector/pgvector:pg16`), JDK 21, Maven.

**Status: none of the three commands has been run. No result is claimed until CI reports one.**

---

## UPDATE 5 — post-authoring review corrections (2026-10-11)

A second read of the staged changes found 3 blockers (would not compile / would not test what it
claimed), 5 security or verification gaps, and 4 documentation defects. All are corrected below.
**Nothing in this update has been compiled or executed** — the authoring sandbox still has only
`python3` and `jq` (no `java`, `mvn`, `docker`, `psql`), so every correction is static reasoning
about the code, not a verified result.

### Blockers

* **C1 — `V26__RlsHardening` would not have compiled.** `log.warn(...)` was called but no `Logger`
  field was declared. Fixed by adding
  `private static final Logger log = LoggerFactory.getLogger(V26__RlsHardening.class);`.
  (Caught by reading, not by compiling — see the caveat above.)
* **C2 — the smoke test could not have exercised the plane split at all.** The script set
  `APP_RESTRICTED_ROLE` / `APP_PLATFORM_READ_DB_USER` / `APP_PLATFORM_READ_DB_PASSWORD`, but
  `app.datasource.restricted-role` and `app.datasource.platform-read.*` are bound **only** in
  `application-staging.yml` (`grep backend/src/main/resources`), and the script runs
  `env -u SPRING_PROFILES_ACTIVE`, so no staging profile is active. `PlatformPlaneConfig` is
  `@ConditionalOnProperty` on that flag, would never activate, `PlatformWriteContext` would be a
  no-op, `jotheca_readall` would never be used — and **every assertion would still have passed**,
  because the app would simply have booted on Spring Boot's single default datasource.
  Fixed by passing them as explicit command-line arguments (which bind from the `Environment`
  regardless of profile) and by a **fail-fast check that both Hikari pool names
  (`jotheca-tenant`, `jotheca-platform-read`) appear in the app log** before any assertion runs.
* **C3 — an assertion that could never pass.** Section 10 asserted
  `assert_contains "response is scoped to library A" "$HTTP_BODY" "$LIB_A_UID"`, but `MemberDto`
  carries the member, not the library UID, so the string can never appear in that body. Rewritten to
  assert the member's own email (library A has exactly one member), paired with the existing
  library-B exclusion on the next line.

### Security gaps

* **S1 — `jotheca_readall` could read credential material.** `GRANT SELECT ON ALL TABLES` gave a
  BYPASSRLS role `users.password_hash`, `users.mfa_secret`, `auth_sessions.token_hash` and
  `verification_tokens.token_hash`. Now `grantSelectOnly` revokes SELECT on the two credential
  tables outright, then revokes table-level SELECT on `users` and **re-grants it column by column**
  for the 12 columns the platform actually needs. Ordering matters and is deliberate: a
  table-level grant is unaffected by a later column-level operation, and column and table privileges
  combine as a *union*, so revoke-at-table-level-then-grant-columns is the only pattern that works.
  (Sources: the PostgreSQL `GRANT` reference, which states that "granting the privilege at the table
  level and then revoking it for one column will not do what you might wish"; and the
  enterpriseDB / Chat2DB column-privilege guides, which describe the same union semantics.)
* **S2 — `jotheca_app` could rewrite migration history.** The migration role's DML grant was issued
  as `GRANT … ON ALL TABLES`, which includes `flyway_schema_history`. `grantDml` now explicitly
  revokes `INSERT`/`UPDATE`/`DELETE`/`TRUNCATE` on that table from the runtime role.
* **S3 — optional roles stayed explicit but were not enforced.** V26 still applies when the roles
  are absent (SQLState `42704` → warn, fail-closed on any other state), so a fresh database
  provisions cleanly. What is new is the **`RlsStartupGuard`**: an `ApplicationRunner` gated on
  `app.datasource.restricted-role=true` that refuses to boot unless the runtime role is neither
  superuser nor BYPASSRLS, every tenant table is ENABLE + FORCE RLS with a fail-closed policy and a
  `WITH CHECK` that references the tenant GUC, `jotheca_readall` exists and is read-only, and the
  credential columns are unreachable. It runs *after* Flyway, so it validates the final state rather
  than the migration's intent. It stays completely inert when `restricted-role=false`.
* **S4 — no post-condition verification.** V26 used to report success even if every grant had
  silently failed. It now re-reads the catalog afterwards (`verifyHardening`) and throws an
  aggregated `SQLException` covering: RLS not ENABLED, not FORCED, policy missing / not PERMISSIVE /
  not `FOR ALL`, a blank `qual` or `with_check`, a predicate that does not reference the tenant GUC,
  a nullable table that lost its NULL-aware predicate, and each least-privilege revocation not being
  in force.
* **S5 — the 29-table list was unenforced (fail-open for future tables).** Rather than a second
  hardcoded copy of the list, `RlsStartupGuard` **derives** the expected set from the catalog
  (`pg_class` ⋈ `pg_attribute` on `tenant_id`), so it cannot go stale and automatically covers any
  table a later migration adds. The hardcoded `TENANT_TABLES` in V26 remains as the explicit
  authoring-time inventory (verified by parsing `db/migration/*.sql`: 36 tables, 29 with
  `tenant_id`, exactly matching), but it is no longer the only line of defence.

### Documentation / correctness defects

* **L1** — `PlaneRoutingInterceptor.afterCompletion` claimed the audit row is always `tenant=NULL`.
  It is not: `TenantResolutionFilter` clears `TenantContext` in its own `finally`, which runs
  *after* this callback, so a platform write that armed `PlatformWriteContext` (`changePlan`,
  `approve`) attributes the row to the target library. Comment corrected to state what actually
  happens. (Behaviour unchanged; only the comment was wrong.)
* **L2** — `PlaneRoutingDataSource`'s javadoc described a "platform-write datasource" that does not
  exist. Corrected, and it now records *why* there is none: a BYPASSRLS write role would be a
  cross-tenant write primitive.
* **L3** — the smoke test's default `PG_PORT` was **5532**, which `infra/docker-compose.yml`
  publishes for its own postgres; the test would have silently attached to the dev database.
  Default moved to 5543, plus a pre-flight check that both the Postgres and app ports are free.
* **L4** — `application-staging.yml` set `spring.flyway.user`/`password` but not `spring.flyway.url`,
  so `SPRING_FLYWAY_URL` was accepted and ignored. The URL is now bound explicitly, defaulting to
  the datasource URL so behaviour is unchanged.

### Residual risk that is documented, not assumed away

With column-level SELECT on `users`, whether `SELECT count(*) FROM users` is still permitted is the
one interaction the PostgreSQL documentation does not settle. `PlatformService.stats()` issues
exactly that query on the platform-read plane, so section 13 of the smoke script now **probes it
directly** and reports it as an explicit warning if it fails, with the documented fallback (a
dedicated platform view, or table-level SELECT on `users` accepting the exposure). It is not
silently assumed to work.

### What is still unverified

Everything in UPDATE 4's "Execution status" section remains true, and now applies to these
corrections too: **no compilation, no unit test, no Testcontainers run, no real-PostgreSQL run, and
no execution of the smoke script or the CI gate has happened in this sandbox.** The smoke script's
own header carries the same warning.

---

## UPDATE 6 — final review of the staged content (2026-10-11)

A line-by-line review of the exact staged blobs (V26, `RlsStartupGuard`, `provision-roles.sh`,
`ci-rls-gate.sh`, the smoke script) found **five concrete defects**. All are corrected. Nothing here
has been compiled or executed — the authoring sandbox still has only `python3` and `jq`.

### Defects found and fixed

* **D1 — broken javadoc reference in V26.** The class comment linked `{@link #verifyCoverage}`, a
  method that does not exist (the method is `verifyHardening`). Corrected, and the sentence now also
  names the startup guard and `{@link #tenantTables()}`.
* **D2 — the guard's catalog discovery could silently omit tenant-owned tables.** The ownership rule
  was `pg_class.relkind = 'r'`, which excludes **partitioned tables** (`relkind = 'p'`). A future
  `CREATE TABLE ... PARTITION OF` tenant table would have been invisible to the guard, so every
  assertion would still have passed. Now `relkind IN ('r', 'p')`, in both the guard and V26's own
  coverage guard.
* **D3 — discovery proved "everything I found is protected" but never "I found everything".** There
  was no cross-check against a documented inventory, so a table that was missing, renamed, or of an
  uncovered relkind was simply absent and the guard passed. The derived set is now compared with
  V26's documented `TENANT_TABLES`, exposed read-only via `V26__RlsHardening.tenantTables()` — a
  single source of truth, not a third copy of the list. A documented table that discovery did not
  find is a **hard failure** ("tenant isolation for it is UNPROVEN, not proven"); a discovered table
  that is protected but absent from the inventory is reported as **drift** in the success log, so the
  inventory cannot rot silently.
* **D4 — the runtime role could inherit its way around the RLS check.** `rolsuper` and
  `rolbypassrls` are attributes of the role *itself* and are **not inherited**, but the effective
  privileges are: a member of a superuser role can do anything and a member of a BYPASSRLS role is
  not confined by RLS. Reading `current_user`'s own flags therefore proved nothing unless its
  memberships were clean too. The guard now rejects any runtime role (or `jotheca_app`) that holds
  `USAGE` on a superuser or BYPASSRLS role, via `pg_has_role`. *(Confirmed against the PostgreSQL
  role-attribute and privilege-inheritance documentation; `provision-roles.sh` itself creates no
  memberships, so this is defence against operator error, not a hole that exists today.)*
* **D5 — "`jotheca_readall` is SELECT-only" was verified on one representative table.** Both V26 and
  the guard probed `memberships` only, so a single stray `GRANT INSERT ON some_table TO
  jotheca_readall` would have left the claim unproven. The guard now checks **every ordinary and
  partitioned table in schema public** for INSERT/UPDATE/DELETE/TRUNCATE in one catalog query.
* **Self-inflicted, caught by re-verification:** the D3 edit initially consumed the closing brace of
  the `pg_policies` try-with-resources, leaving the cross-check inside the block and the file
  unbalanced. Caught by the brace-balance check, repaired, and re-verified (raw `{` == `}` on both
  files).

### Verified, no change needed

* **Transaction safety / idempotency (V26).** Flyway runs each migration in one transaction and
  PostgreSQL has transactional DDL, so `ALTER TABLE … ENABLE/FORCE`, `DROP POLICY IF EXISTS` +
  `CREATE POLICY`, and the `GRANT`/`REVOKE` statements all commit or roll back together with
  `verifyHardening`. Re-running V26 on an already-hardened database succeeds: every statement is
  either idempotent or a no-op. A missing table fails with SQLState `42P01` and aborts.
* **V26 cannot report success with incomplete RLS policies.** `verifyHardening` re-reads the catalog
  and throws on any of: RLS not ENABLED, not FORCED, policy missing / not PERMISSIVE / not `FOR ALL`,
  blank or non-GUC-referencing `qual`/`with_check`, a nullable table that lost its NULL-aware
  predicate, a documented table absent from the catalog, a `tenant_id` table with no policy, or any
  least-privilege revocation not in force. Flyway then rolls the whole migration back.
* **Effective privileges account for inheritance and pre-existing grants.** Every privilege check
  uses `has_table_privilege` / `has_column_privilege` with an explicit role name, which evaluate the
  **effective** privilege — they already account for grants inherited via role membership, grants to
  `PUBLIC`, and object ownership. A `PUBLIC` grant on `flyway_schema_history` or on `users` therefore
  cannot be hidden from the check, and a `REVOKE … FROM jotheca_app` that silently changed nothing
  ("no privileges could be revoked") is caught by the verification rather than assumed to have worked.
* **The migration *can* still report success when the roles do not exist.** That is the approved S3
  behaviour (V26 must apply on any target, including a fresh database), and it is compensated at
  startup: `RlsStartupGuard` refuses to boot under `restricted-role=true` unless `jotheca_app` and
  `jotheca_readall` exist, are confined, and hold exactly the intended privileges. Stated plainly:
  **RLS policies are fail-fast at migration time; role privileges are fail-fast at startup time.**
* **`provision-roles.sh`** creates no role memberships, no `GRANT … TO PUBLIC`, and no
  `ALTER DEFAULT PRIVILEGES`; it sets `NOSUPERUSER NOBYPASSRLS` on `jotheca_migrate` and
  `jotheca_app`, `BYPASSRLS` on `jotheca_readall`, and is idempotent (re-running only re-asserts
  attributes and password).
* **CI gate fails closed** on: `docker` not on PATH, `docker info` failure, missing reports
  directory, a missing `TEST-…RlsTenantIsolationTest.xml`, `tests == 0`, `skipped != 0`,
  `failures`/`errors`, and **any** skip in **any** `TEST-*.xml` in the run. A missing attribute
  defaults to `0`, so a malformed report fails rather than passes.
* **Smoke script fails closed** on missing plane pools (exits before any assertion), on any failed
  HTTP or SQL assertion (accumulated, final exit 1), and on database security regressions
  (`expect_sql_failure` delegates to `psql_as_rc`, which returns the *real* exit code, so an
  expected-to-fail statement that unexpectedly succeeds is reported as `LEAKED`, not `OK`).

### Still unverified

No compilation, no unit test, no Testcontainers run, no real-PostgreSQL run, no smoke-script
execution, and no CI evidence. Every test claim in this document remains UNEXECUTED until CI reports.
