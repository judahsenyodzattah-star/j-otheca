# Arena Progress Record — J'otheca

> **Purpose of this file:** a continuation record so work is not lost if the Arena
> session is interrupted. It is written to be accurate about what was *verified*
> versus what was *not*, and it contains **no credentials or secrets**.

| | |
|---|---|
| **Repository** | `judahsenyodzattah-star/j-otheca` |
| **Branch** | `arena/a775b9a4-j-otheca` |
| **Snapshot commit** | `750913be406eaf1f4fe79b79a3cee5abe3fc1de9` (`750913b`) |
| **Snapshot parent** | `7b2dd73d5fc072c708d4d2beeda1cba939a66770` (= `main`, = `origin/main`) |
| **Record last updated** | 2026-10-13 |
| **Remote** | `https://github.com/judahsenyodzattah-star/j-otheca.git` |

---

## 1. Project purpose

**J'otheca** is a multi-tenant library-management platform with AI-powered
knowledge discovery. A single library (the *tenant*) manages its catalog,
members, circulation (loans, reservations, fines), media, publishing, billing
and analytics; many libraries share one PostgreSQL database and one application
instance. A *platform plane* serves cross-tenant reads for a SUPER_ADMIN.

Three deliverables:

| Component | Technology | Location |
|---|---|---|
| Backend API | Spring Boot **3.5.16**, Java **21**, Maven | `backend/` |
| Mobile + web client | Flutter (Dart `^3.5.0`) | `mobile/` |
| Database | PostgreSQL **16 + pgvector**, schema owned by Flyway | `backend/src/main/resources/db/migration/` |

Supporting infrastructure in `infra/`: `docker-compose.yml`,
`db/provision-roles.sh`, `.env.staging.example`, `env/backend.env.example`.

---

## 2. Verified architecture

Verified by reading the source, not by assumption.

- **Modular monolith.** `com.jotheca.JothecaApplication` is a plain
  `@SpringBootApplication` + `@EnableJpaAuditing` +
  `@EnableSpringDataWebSupport`. Package-by-feature inside `com.jotheca`:
  `ai`, `analytics`, `audit`, `authors`, `billing`, `catalog`, `circulation`,
  `common`, `identity`, `media`, `membership`, `onboarding`, `platform`,
  `publishing`, `seed`, `tenancy`.
- **Schema is owned exclusively by Flyway.** `spring.jpa.hibernate.ddl-auto: none`
  in `application.yml`. 26 migration versions: 15 SQL + 11 Java
  (`V1`…`V26`). The Java migrations exist because RLS policies and
  Postgres-only indexes cannot be expressed portably.
- **Nine layers of tenant isolation**, of which PostgreSQL RLS is the backstop:
  1. `TenantResolutionFilter` validates `X-Library-Id` server-side against an
     APPROVED membership (the header is never trusted alone)
  2. `TenantContext` (fail-closed, cleared in a `finally`)
  3. Hibernate `tenantFilter` on the request-bound persistence context
  4. repository-level scoping
  5. service-level `TenantGuard`
  6. RLS policies `<table>_tenant_isolation` on every tenant table
  7. `FORCE ROW LEVEL SECURITY` so the table owner is also confined
  8. `WITH CHECK` on the policies so cross-tenant writes are rejected
  9. tenant-pinned AI/ANN queries
- **Stateless JWT authentication.** JJWT 0.12.6, HMAC-SHA, access token 15 min,
  refresh token 14 days with rotation. `JwtService` **refuses to start** if
  `app.jwt-secret` is under 48 characters. BCrypt password hashing.
  Session creation policy `STATELESS`; CSRF disabled deliberately (no cookie
  transport exists).
- **Two database planes (Phase A).** `PlaneContext` selects
  `TENANT` (`jotheca_app`, NOSUPERUSER + NOBYPASSRLS ⇒ RLS-enforced) or
  `PLATFORM_READ` (`jotheca_readall`, BYPASSRLS, SELECT-only, audited).
  **There is deliberately no cross-tenant WRITE role**: platform writes run as
  `jotheca_app` with the tenant context armed to the single target library, so
  they are RLS-confined exactly like tenant traffic.
- **Actuator is minimal.** `management.endpoints.web.exposure.include: health`
  — only `/actuator/health` is exposed, and it is `permitAll` so a platform
  healthcheck can use it.
- **CORS** comes from `app.cors-allowed-origin-patterns` (`CORS_ORIGINS`),
  comma-separated, never a hardcoded `*` outside dev. Allowed headers include
  `Authorization`, `Content-Type`, `X-Library-Id`.

---

## 3. Current implementation phase

- **Phases 0–11** are marked complete in `README.md` (bootstrap, identity,
  tenancy, onboarding/billing, catalog, circulation, publishing, media/reviews,
  security/audit, quotas/billing/analytics, AI, hardening).
- **LibraNet → J'otheca rebrand**: complete in the worktree, now committed.
- **Phase A RLS tenant-isolation remediation**: implemented and committed,
  **not runtime-verified** (see §6).
- **Deployment to Railway**: audited only. **Nothing has been deployed.**

---

## 4. Completed work and exact file paths

### 4.1 Rebrand (LibraNet → J'otheca)

Mechanical rename, no logic change. Transform rules and full file inventory are
in `docs/REBRAND-REPORT-2026-10-10.md`.

- 270 `com/libranet/**` → `com/jotheca/**` moves (recorded by git as renames)
- 1 deletion: `backend/src/main/java/com/libranet/identity/security/InMemoryRateLimiter.java`
  (superseded by a rewritten `com/jotheca` copy, so not detected as a rename)
- 77 files modified in place across `README.md`, `docs/**` (incl. `docs/academic/**`),
  `mobile/**` (Flutter, Android, web, Windows), `scripts/**`, `infra/**`,
  `.github/workflows/ci.yml`, `backend/**`

### 4.2 Phase A RLS tenant-isolation hardening (new code)

| File | Lines | Role |
|---|---|---|
| `backend/src/main/java/db/migration/V26__RlsHardening.java` | 396 | Migration: ENABLE+FORCE RLS on all 29 tenant tables, revokes DML on `flyway_schema_history`, revokes the two credential tables from the platform read role, replaces table-level SELECT on `users` with a column-level grant, then self-verifies and throws on any drift |
| `backend/src/main/java/com/jotheca/common/datasource/RlsStartupGuard.java` | 334 | Refuses to boot when `app.datasource.restricted-role=true` but the DB does not actually enforce isolation. Derives the tenant-table set from the catalog and cross-checks it against V26's inventory |
| `backend/src/main/java/com/jotheca/common/datasource/PlatformPlaneConfig.java` | 97 | Two-pool config (`jotheca-tenant`, `jotheca-platform-read`), active only under `restricted-role=true` |
| `backend/src/main/java/com/jotheca/common/datasource/PlaneRoutingInterceptor.java` | 71 | Assigns the plane per request; audits platform access via the TENANT plane |
| `backend/src/main/java/com/jotheca/common/datasource/PlaneRoutingDataSource.java` | 20 | Routes each acquired connection by `PlaneContext` |
| `backend/src/main/java/com/jotheca/common/datasource/PlaneContext.java` | 41 | `TENANT` / `PLATFORM_READ` thread-local; defaults to TENANT (fail-safe) |
| `backend/src/main/java/com/jotheca/common/tenant/PlatformWriteContext.java` | 61 | Arms the tenant context for a single target library on a platform write |
| `backend/src/main/java/com/jotheca/common/tenant/TenantAwareDataSource.java` | 73 | Tenant-scoped datasource façade |
| `backend/src/main/java/com/jotheca/identity/security/InMemoryRateLimiter.java` | 52 | Rewritten sliding-window limiter with idle-key sweeping |
| `backend/src/main/resources/db/migration/V25__loan_overdue_reminders.sql` | 4 | Adds `loans.reminder_sent_at` (additive only) |
| `infra/db/provision-roles.sh` | 61 | Idempotent provisioning of `jotheca_migrate`, `jotheca_app`, `jotheca_readall` from env only |
| `scripts/ci-rls-gate.sh` | 103 | Fails CI when the RLS/Testcontainers suite is skipped |
| `scripts/restricted-role-smoke.sh` | 540 | End-to-end proof against real Postgres: real HTTP + real RLS, fails closed |

Modified in place for Phase A:
`.github/workflows/ci.yml`, `backend/pom.xml`,
`backend/src/main/resources/application-staging.yml`,
`infra/.env.staging.example`, `infra/docker-compose.yml`.

### 4.3 Tests added

| File | Lines |
|---|---|
| `backend/src/test/java/com/jotheca/tenancy/RlsTenantIsolationTest.java` | 254 |
| `backend/src/test/java/com/jotheca/common/datasource/PlaneRoutingTest.java` | 131 |
| `backend/src/test/java/com/jotheca/identity/CorsConfigTest.java` | 42 |
| `backend/src/test/java/com/jotheca/billing/PaystackSignatureTest.java` | 70 |
| `backend/src/test/java/com/jotheca/billing/PaymentWebhookSecurityTest.java` | 55 |
| `backend/src/test/java/com/jotheca/circulation/OverdueReminderTest.java` | 113 |
| `backend/src/test/java/com/jotheca/ai/AiRetrievalEvaluationTest.java` | 181 |
| `mobile/test/refresh_policy_test.dart` | 48 |

### 4.4 Documentation added

`docs/REBRAND-REPORT-2026-10-10.md`, `docs/SECURITY-AUDIT-2026-10-10.md`,
`docs/SYSTEM-ANALYSIS-2026-10-10.md`, `docs/access-and-execution-report.md`,
`docs/system-audit-implementation-report.md`,
`docs/audits/BUGFIX-REPORT-2026-10-10.md`,
`docs/audits/COMPREHENSIVE-SECURITY-AUDIT-2026-10-11.md`,
`docs/audits/PHASE-A-REMEDIATION-DESIGN-2026-10-11.md` (636 lines — the Phase A
design, including UPDATE 6 which records the five defects found in final review
and their fixes).

---

## 5. Commands and test results

### 5.1 Actually executed in the Arena sandbox

| Command | Result |
|---|---|
| `bash -n` on 9 shell scripts (`provision-roles.sh`, `backup.sh`, `ci-rls-gate.sh`, `demo-p9/p10/p11.sh`, `load-test.sh`, `restore.sh`, `restricted-role-smoke.sh`) | **exit 0** — all syntactically valid |
| YAML parse of `application.yml`, `application-dev.yml`, `application-staging.yml`, `infra/docker-compose.yml`, `.github/workflows/ci.yml`, `.github/workflows/pages.yml` | **OK** — all parse |
| XML parse of `backend/pom.xml` | **OK** |
| `bash scripts/ci-rls-gate.sh` | **exit 1** — fail-closed: `docker is not on PATH` |
| `bash scripts/restricted-role-smoke.sh` | **exit 1** — fail-closed: `Docker is required … not found on PATH` |

Both scripts returning exit 1 is the **correct, designed behaviour** — they are
built to fail closed rather than silently skip.

### 5.2 NOT executed — remain unproven

The sandbox has **no `java`, `javac`, `mvn`, `docker`, `psql`, `pg_isready` or
`pg_dump`**, and `repo.maven.apache.org`, `registry-1.docker.io` and
`auth.docker.io` are unreachable. Therefore the following have **never run** and
must not be reported as passing:

- `mvn -q -DskipTests package` — **compilation is unverified**
- `mvn -B verify` — unit, security, isolation and Testcontainers suites unverified
- `RlsTenantIsolationTest` — unverified (needs Docker)
- The Docker image build (`backend/Dockerfile`) — unverified
- Execution of the Flyway chain `V1`…`V26` on a real PostgreSQL — unverified
- Any Flutter `analyze` / `test` / `build web` — unverified (no Flutter SDK)

**First thing to do on a machine with a JDK:** `cd backend && mvn -B verify`.

---

## 6. Known errors, blockers and defects

### 6.1 Defect carried into the checkpoint (do not lose this)

`backend/src/main/java/com/jotheca/common/datasource/PlaneContext.java`
declares `package com.jotheca.common.tenant;` but physically lives in
`com/jotheca/common/datasource/`.

- All five consumers import `com.jotheca.common.tenant.PlaneContext`, which
  matches the *declaration*, so this most likely compiles: `javac` does not
  enforce package/directory agreement when given an explicit file list, which is
  what Maven does.
- It is nevertheless a latent build fragility and a maintenance trap.
- **Recommended fix:** move the file to
  `backend/src/main/java/com/jotheca/common/tenant/PlaneContext.java`.
- Left unchanged deliberately so the checkpoint is a faithful snapshot.

### 6.2 Deployment blockers found during the Railway audit

1. **Port.** The app has **no** `server.port` / `PORT` handling anywhere
   (`grep` across all `.java`, `.yml`, `.sh`, `.md` finds exactly one hit, in a
   test script). It binds Spring Boot's default **8080**. Railway injects `PORT`
   and requires the app to listen on `0.0.0.0:$PORT`. Fix: make the Dockerfile
   entrypoint `sh -c "exec java -jar app.jar --server.port=${PORT:-8080}"`.
2. **pgvector.** `V17__RlsPoliciesAi.java` runs
   `CREATE EXTENSION IF NOT EXISTS vector` unconditionally on any PostgreSQL
   connection, and `V19__TrigramSearchIndexes.java` runs
   `CREATE EXTENSION IF NOT EXISTS pg_trgm`. Railway's stock managed Postgres
   does **not** ship pgvector; a pgvector-enabled Postgres service is required.
   This is unavoidable without editing a migration.
3. **`payments.provider` is hardcoded.** The uncommitted-then-committed Phase A
   change added `app.payments.provider: paystack` to `application-staging.yml`
   **with no environment override**. `PaystackPaymentProvider` throws
   `IllegalStateException` in its constructor when `PAYSTACK_SECRET_KEY` is
   blank ⇒ **the application context fails to start**. Note the repo's own
   `staging-migration-smoke` CI job boots the staging profile *without* that
   variable, so it would now fail. Make it `${APP_PAYMENTS_PROVIDER:mock}`.
4. **`DevDataSeeder` is `@Profile("dev")` only** ⇒ a freshly hosted database has
   **zero users**. The README's demo accounts exist only under `dev`. Login
   testing on a hosted instance must go through `/api/v1/auth/register`.
5. **Flutter web never sets its API base URL.** `ApiClient.defaultBaseUrl()`
   reads `String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080')`.
   Neither `.github/workflows/ci.yml` (`flutter build web --release`) nor
   `.github/workflows/pages.yml` (`--base-href /j-otheca/`) passes
   `--dart-define=API_BASE_URL=…`, so every web build targets localhost.
6. **`infra/docker-compose.yml` references build contexts that do not exist:**
   `../ai-service` and `../frontend`. The repo has only `backend/`, `docs/`,
   `infra/`, `mobile/`, `scripts/`, `.github/`, `.vscode/`.
7. **Doc drift:** `docs/ops/STAGING-DEPLOY.md` says `docker compose --profile full`
   but no service declares a profile, and says "runs Flyway V1–V19" when the
   chain now runs to V26.
8. **No `.dockerignore`** exists, so `backend/target/` can leak into the image.
9. **Health-check risk (unverified):** the staging profile sets
   `management.health.redis.enabled: true`, and `spring.mail.host` defaults to
   `localhost` in both the default and staging profiles. Without a reachable
   Redis or SMTP server, `/actuator/health` may return **503**, which would fail
   a platform healthcheck. Mitigation: `REDIS_HEALTH_ENABLED=false` and
   `MANAGEMENT_HEALTH_MAIL_ENABLED=false`, or provide real Redis/SMTP.
10. **Spring Boot does not accept a raw `DATABASE_URL`.** It must be decomposed
    into `SPRING_DATASOURCE_URL` (`jdbc:postgresql://…`), `SPRING_DATASOURCE_USERNAME`
    and `SPRING_DATASOURCE_PASSWORD`, or taken from `PGHOST`/`PGPORT`/`PGUSER`/
    `PGPASSWORD`/`PGDATABASE`.

### 6.3 Migration safety — verified clean

Cross-referenced every `CREATE TABLE`, `REFERENCES`, `ALTER TABLE`,
`CREATE INDEX ON`, `INSERT INTO`, `UPDATE` and `DELETE FROM` across all 26
migrations against the version that creates each table.

- **No forward references. No missing tables. No ordering bugs.**
- The borrowing tables `loan_policies`, `loans`, `reservations`, `fines`,
  `notifications` are created in **V7** and first referenced by the RLS
  migration **V8** — the immediately following version. ✓
- 36 tables total. Every RLS migration's table list resolves at or before its
  own version (V2→V1, V6→V5, V8→V7, V10→V9, V12→V11, V15→V3/V14, V17→V16,
  V21→V20, V24→V23, V26→all).

### 6.4 Secret hygiene — verified clean

- No `.env`, `.pem`, `.key`, `.p12`, `.pfx`, `.jks`, `.keystore`, `.dump`,
  `.bak`, `.sqlite` or `.sql.gz` file is tracked or staged.
- A secret-shaped scan across all 647 candidate files found only:
  obviously fake `sk_test_…` fixtures in `PaystackSignatureTest.java`, a
  timing-equalizer constant literally named "not-a-real-secret" in
  `AuthService.java`, and placeholder values in `*.example` files.
- `infra/db/provision-roles.sh` and `scripts/restricted-role-smoke.sh` read
  passwords only from the environment / runtime-generated values.

---

## 7. Important architecture and security decisions

These are deliberate. Do not "fix" them without understanding why.

1. **Schema comes only from Flyway.** `ddl-auto: none`. Hibernate never creates
   or alters tables. Any schema change is a new migration.
2. **RLS is the backstop, not the only layer.** Nine layers exist so that a
   mistake in one does not leak data. `V26` hardens the RLS layer specifically:
   `FORCE ROW LEVEL SECURITY` (so the owner is confined too) and `WITH CHECK`
   (so cross-tenant writes are rejected, not just cross-tenant reads).
3. **No cross-tenant WRITE role exists.** This is the single most important
   Phase A decision. Platform writes run as `jotheca_app` with the tenant
   context armed to one library. A dedicated BYPASSRLS write role would be a
   cross-tenant write primitive.
4. **`jotheca_readall` is SELECT-only and credential-blind.** `V26` revokes
   `auth_sessions` and `verification_tokens` entirely, and replaces
   table-level SELECT on `users` with a column-level grant so `password_hash`
   and `mfa_secret` are unreachable even though the role has BYPASSRLS.
5. **The runtime role must never be a superuser, a table owner, or hold
   BYPASSRLS — directly or by inheritance.** `RlsStartupGuard` checks all of
   these, including inherited membership via `pg_has_role`, and refuses to boot
   otherwise. This is why `APP_RESTRICTED_ROLE` must stay `false` until the
   roles are actually provisioned.
6. **`RlsStartupGuard` derives its table set from the catalog** rather than
   hardcoding a list, so it cannot go stale — then cross-checks against V26's
   documented inventory, because derivation alone cannot prove nothing was
   omitted.
7. **Fail-closed everywhere.** `JwtService` refuses a weak secret;
   `PaystackPaymentProvider` refuses a missing key; `RateLimitConfig` refuses a
   Redis backend with no connection; both CI scripts refuse to run when their
   prerequisites are absent rather than skipping.
8. **`TenantContext` is cleared in a `finally`** in `TenantResolutionFilter`,
   and `PlaneRoutingInterceptor` clears the plane in its own `finally` — which
   runs *after* `afterCompletion`, so the ordering matters.
9. **Rate limiting fails open by design** when Redis is unreachable: a cache
   outage must not become a login outage. Durable account lockout plus BCrypt
   cost still protect credentials, and the failure is logged loudly.
10. **Migrations are append-only** under the change-control rule. No destructive
    migration has been needed so far; `V25` and `V26` are additive.
11. **Actuator exposes only `health`.** Nothing else is reachable over HTTP.

---

## 8. Remaining tasks

### 8.1 Blocking — must be done before any hosted deployment

1. Fix the `PlaneContext.java` package/directory mismatch (§6.1).
2. Fix the Dockerfile port binding (§6.2.1).
3. Provision a **pgvector-enabled** PostgreSQL (§6.2.2).
4. Make `app.payments.provider` overridable via `APP_PAYMENTS_PROVIDER`
   (§6.2.3) and repair the `staging-migration-smoke` CI job.
5. Pass `--dart-define=API_BASE_URL=…` in both Flutter web build steps (§6.2.5).
6. Add `backend/.dockerignore` (§6.2.8).
7. Run `cd backend && mvn -B verify` on a machine with JDK 21 + Maven, and fix
   whatever it reports. **Nothing in this checkpoint has been compiled.**

### 8.2 Non-blocking cleanups

8. Remove the non-existent `ai-service` and `frontend` services from
   `infra/docker-compose.yml`, or add those directories (§6.2.6).
9. Fix `docs/ops/STAGING-DEPLOY.md` (`--profile full`, "V1–V19") (§6.2.7).
10. Decide whether the Redis and mail health indicators should be disabled by
    default outside dev (§6.2.9).
11. The comment in `application-staging.yml` claiming `SPRING_FLYWAY_URL` "was
    previously accepted and ignored" is inaccurate for Spring Boot's relaxed
    binding; the behaviour is unchanged either way, but the comment misleads.

### 8.3 Phase A rollout (optional, deliberate)

12. Run `infra/db/provision-roles.sh` against the target database.
13. Set `APP_RESTRICTED_ROLE=true` only once `RlsStartupGuard` passes.
14. Set `APP_RATE_LIMIT_TRUSTED_PROXIES` to the ingress IPs so
    `X-Forwarded-For` is honoured for rate limiting.

---

## 9. Next recommended task

**Compile and test the checkpoint.** On any machine with JDK 21 and Maven:

```bash
git clone https://github.com/judahsenyodzattah-star/j-otheca.git
cd j-otheca && git checkout arena/a775b9a4-j-otheca
cd backend && mvn -B verify
```

Everything in §5.2 is currently unproven, and `mvn verify` is the single
cheapest way to convert most of it into evidence. Fix the `PlaneContext`
package mismatch (§6.1) first, since it is the most likely cause of a surprise.

Only after that is green: work through §8.1 in order, then re-audit deployment
readiness. **Do not begin a new feature phase until the build is proven.**

---

## 10. How this work is preserved

- Local branch `arena/a775b9a4-j-otheca` at `750913b`, one commit ahead of
  `main`.
- Pushed to `origin/arena/a775b9a4-j-otheca` on
  `https://github.com/judahsenyodzattah-star/j-otheca.git`.
- To retrieve on Windows:

```bash
git clone https://github.com/judahsenyodzattah-star/j-otheca.git
cd j-otheca
git checkout arena/a775b9a4-j-otheca
git log -1 --stat        # 750913b — 377 files changed, +6838 / -1819
```

- To export a patch without cloning:
  `git fetch origin arena/a775b9a4-j-otheca && git format-patch main..origin/arena/a775b9a4-j-otheca -o ./checkpoint`

`main` was **not** modified, force-pushed, reset or switched. No branch other
than `arena/a775b9a4-j-otheca` was created or pushed.
