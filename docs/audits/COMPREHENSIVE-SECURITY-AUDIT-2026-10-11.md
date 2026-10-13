# J'otheca — Comprehensive Security Audit, Tenant‑Isolation Verification & Remediation

**Date:** 2026‑10‑11 · **Branch:** `arena/a775b9a4-j-otheca` @ `50f8426` · **Base:** `main` `7b2dd73`
**Mode:** evidence‑based static/code‑and‑config inspection. **No code was changed in this audit** — findings and the remediation plan are presented for review before any edit (per §15/§17).

---

## 0. Method, and the verification limits that shape every status below

This workspace has **no Java compiler** (`javac`), **Maven/Gradle TLS‑blocked** (Maven Central returns TLS‑EOF — verified with real HTTPS GETs), **no PostgreSQL/Docker**, and **no Flutter/Dart**. Consequently:

- **No compilation, no JUnit execution, no PostgreSQL RLS runtime test, no dependency‑vulnerability scan** could be performed here.
- Per the required standard, **static inspection is NOT counted as a passing runtime test.** Anything that needs the JVM, a live Postgres, Docker, or the mobile toolchain is **NOT VERIFIED**, with the exact limitation stated.
- The one thing I *did* execute is a faithful **Python mirror of the Paystack HMAC algorithm** (11/11 vectors) and a **Java parser syntax pass** (see §7). Those are the only executed checks.

Statuses used: **PASS WITH EVIDENCE**, **FAIL**, **PARTIAL**, **NOT VERIFIED**, **NOT APPLICABLE**.

---

## Deliverable 1 — Control status summary

| # | Priority | Control | Status | One‑line basis |
|---|----------|---------|--------|----------------|
| 1.1 | P0 | Tenant identity derived from authenticated context; `X-Library-Id` validated against APPROVED membership | **PARTIAL** (sound by inspection; runtime NOT VERIFIED) | `TenantResolutionFilter` validates header server‑side; cross‑tenant tests exist but were not executed |
| 1.2 | P0 | Untrusted header never treated as authorization | **PARTIAL** (NOT VERIFIED at runtime) | Header is resolved→validated against membership; design correct, not executed |
| 1.3 | P0 | Tenant context set/cleared per request; fail‑closed | **PARTIAL** (NOT VERIFIED) | ThreadLocal set only after validation, cleared in `finally`; not executed |
| 1.4 | P0 | **PostgreSQL RLS as an effective DB backstop** | **FAIL** | App connects as superuser/owner → RLS bypassed; policy fail‑open; no `WITH CHECK`; no `FORCE` |
| 1.5 | P0 | Least‑privilege app DB role / RLS not bypassed by role | **FAIL** | Single superuser role `jotheca`; no app role, no GRANTs, no `NOBYPASSRLS` |
| 1.6 | P0 | RLS covered by PostgreSQL integration tests | **FAIL / NOT VERIFIED** | No PG RLS test exists; H2 suite is a no‑op for RLS |
| 2.1 | P1 | Every protected endpoint authenticated; platform gated | **PARTIAL** (NOT VERIFIED) | `SecurityConfig`: `anyRequest().authenticated()`, `/platform/**`→`SUPER_ADMIN`; not executed |
| 2.2 | P1 | JWT signature/algorithm/secret strength | **PARTIAL** (NOT VERIFIED) | ≥48‑char secret enforced, `verifyWith(key)` HMAC; expiry/algorithm pinning not runtime‑confirmed |
| 2.3 | P1 | Password hashing (adaptive) | **PASS WITH EVIDENCE** (design; runtime NOT VERIFIED) | `BCryptPasswordEncoder`; timing‑equalizer + durable lockout against enumeration |
| 2.4 | P1 | Refresh/verification token hygiene | **PASS WITH EVIDENCE** (design) | Single‑use `usedAt`, expiry, refresh‑rotation with reuse‑detection (family revoke + audit) |
| 2.5 | P1 | Rate limiting on auth/abuse endpoints | **PARTIAL** | Present, but **R2** (trusts `X-Forwarded-For`) and **R3** (no idle‑key eviction) weaken it |
| 3.1 | P1 | Webhook signature validation | **PASS WITH EVIDENCE** (algorithm executed via mirror) | HMAC‑SHA512 raw‑body, constant‑time, fail‑closed — hardened this session; 11/11 mirror vectors pass |
| 3.2 | P1 | Webhook idempotency / replay / amount+reference verification | **PARTIAL** (NOT VERIFIED) | Checkout reuse is idempotent‑feeling; webhook duplicate/amount checks not confirmed in this pass |
| 3.3 | P1 | Mock provider restricted to dev/test | **PASS WITH EVIDENCE** (design) | `MockPaymentProvider` `@Profile({"dev","test"})`, `validSignature`→false; staging forces `paystack` (DEFECT‑1, fixed earlier) |
| 4.1 | P1 | Parameterized queries / no SQL injection | **PARTIAL** (NOT VERIFIED) | JPA derived queries; one `SET LOCAL app.tenant_id='…'` built by string concat (low risk — resolved id) |
| 4.2 | P1 | Concurrency‑safe circulation | **PASS WITH EVIDENCE** (design) | Pessimistic row lock added this session (`findByIdForUpdate`) serializes issue; race test not executed |
| 5.1 | P1 | Patrons see only their own records | **PARTIAL** (NOT VERIFIED) | `myActiveLoans` scoped by `ctx.membershipId()`; not executed |
| 6.1 | P1 | AI retrieval tenant‑filtered before scoring | **PASS WITH EVIDENCE** (design) | `WHERE tenant_id=:current` before vector math; pgvector backend keeps same predicate |
| 7.1 | P1 | Object storage tenant isolation | **PARTIAL** (NOT VERIFIED) | Tenant‑prefixed keys `t/{tenantId}/…` + `TenantGuard`; signed‑URL lifetime/ownership not runtime‑confirmed |
| 8.1 | P2 | CORS restrictive | **NOT VERIFIED** | `CorsConfigTest` exists; allowed origins not captured/executed here |
| 9.1 | P2 | Forwarded headers trusted only from known proxies | **FAIL** | **R2**: `RateLimitFilter` trusts `X-Forwarded-For` unconditionally |
| 10.1 | P2 | Production secrets not in VCS | **PASS WITH EVIDENCE** | Repo scan clean (no live keys/private keys); dev creds are documented demo‑only; JWT secret is dev‑only + length‑guarded |
| 11.1 | P2 | Dependency vulnerability scan | **NOT VERIFIED** | No scanner / vuln DB reachable; **not** claimed clean. Versions: Spring Boot 3.5.16, jjwt 0.12.6, Flutter ^3.5.0 |
| 12.1 | P2 | Security‑relevant audit logging | **PARTIAL** (NOT VERIFIED) | `AuditService` logs auth/billing/circulation events; retention/access controls not assessed |

---

## Deliverable 2 — Prioritized vulnerability register

| ID | Sev | Component / file | Finding & attack scenario | Evidence | Fix (proposed) | Regression test |
|----|-----|------------------|---------------------------|----------|----------------|-----------------|
| **VULN‑1** | **CRITICAL** | `infra/docker-compose.yml`, `application.yml`, RLS migrations `db/migration/V*` | App connects to Postgres as `jotheca`, the **superuser + table owner**. PostgreSQL **bypasses RLS for superusers/owners** (FORCE doesn't help superusers). → the entire RLS "backstop (layer 6/9)" never filters app queries; any app‑layer slip = cross‑tenant read/write with no DB stop. | `POSTGRES_USER=jotheca`; `SPRING_DATASOURCE_USERNAME=jotheca`; migrations create tables as this user; no `CREATE ROLE`/`GRANT`/`NOBYPASSRLS` anywhere | Create a **least‑privilege, non‑owner, `NOBYPASSRLS` app role**; `GRANT` DML only; connect the app as it; keep migrations on a separate admin role | PG integration test asserting app role **cannot** read another tenant's row even with filter/guard removed |
| **VULN‑2** | **HIGH** | RLS policies (all `V*__RlsPolicies*.java`) | Policy `USING (app.tenant_id IS NULL OR ='' OR tenant_id=current_setting(...))` is **fail‑open**: any connection/transaction where the GUC isn't armed sees **all tenants**. | `V2__RlsPolicies.java:33‑40` (template for all) | Fail‑closed policy (`tenant_id = current_setting('app.tenant_id')` with a safe default, or a dedicated tenant role + `SET ROLE`) | PG test: unset GUC → zero rows, not all rows |
| **VULN‑3** | **HIGH** | RLS policies | **No `WITH CHECK`** → INSERT/UPDATE not constrained; a bug/request can write or re‑assign rows to another `tenant_id`. | grep `with check` across migrations = none | Add `WITH CHECK (tenant_id = current_setting('app.tenant_id'))` | PG test: app role cannot INSERT a foreign‑tenant row |
| **VULN‑4** | **HIGH** | test suite | **No PostgreSQL RLS test**; RLS is a no‑op on H2, so nothing exercises the DB layer. | `TenantIsolationTest` is H2 app‑layer only; no PG RLS test file | Add `@Testcontainers`/CI Postgres RLS cross‑tenant test | New PG RLS suite |
| **VULN‑5** | **MED** | `RateLimitFilter.java:61` | **R2**: trusts `X-Forwarded-For` → attacker spoofs the header to evade per‑IP limits (brute force / enumeration). | reads `X-Forwarded-For` with no trusted‑proxy check | Trust XFF only from configured proxies (`server.forward-headers-strategy`/`ForwardedHeaderFilter` with allowlist) | RateLimitTest with spoofed vs real IP |
| **VULN‑6** | **MED** | `InMemoryRateLimiter.java` | **R3**: idle keys never evicted → unbounded map growth (memory DoS) over time. | no eviction/size cap seen | Bounded LRU/TTL eviction or Redis limiter | Unit test: map bounded after idle keys |
| **VULN‑7** | **MED** | RLS coverage + `TenantScopedEntity` | Some tenant tables may lack policies (e.g. `author_profiles`), and the Hibernate `@Filter` layer silently depends on **OSIV staying enabled**; disabling OSIV drops a layer. | covered‑table list (§Deliv.4); `open-in-view` unset→default true | Complete policy coverage; pin `spring.jpa.open-in-view=true` with a comment + test asserting filter armed | Test: filter actually restricts a repo query |
| **VULN‑8** | **LOW** | `GlobalExceptionHandler` | **R4**: constraint/optimistic‑lock failures map to **500**, not 409 — masks client errors, minor info/UX. | deferred in BUGFIX‑REPORT | Map `ConstraintViolation`/`OptimisticLock`→409 | Exception‑mapping test |
| **VULN‑9** | **LOW** | `CirculationService.toDto` | **R5**: loan‑list N+1 queries (perf under load). | deferred | Batch fetch | Perf/regression test |
| **VULN‑10** | **INFO** | `TenantAwareDataSource.java:58` | `SET LOCAL app.tenant_id='…'` by string concat; safe today (id is a resolved UUID) but fragile. | line 58 | Use `set_config('app.tenant_id', ?, false)` bound param | — |
| *(resolved)* | — | `MockPaymentProvider`/staging | **DEFECT‑1** payment fail‑open — **fixed** earlier this session. | `PaymentWebhookSecurityTest` | — | — |

---

## Deliverable 3 — Tenant‑isolation matrix (Library A vs Library B)

Design verified by inspection; **execution NOT VERIFIED** (no JVM). "Expected" = required behavior; "Design" = what the code+tests assert.

| Test (file) | Layer | Expected | Design result | Execution |
|---|---|---|---|---|
| `callerWithoutMembershipCannotEnterAnotherTenant` | App | 403 | denies (membership check) | NOT VERIFIED |
| `pendingMembershipDoesNotGrantTenantAccess` | App | 403 | denies (APPROVED only) | NOT VERIFIED |
| `patronCannotUseStaffEndpointsInsideOwnTenant` | App | 403 | denies (role) | NOT VERIFIED |
| `unknownTenantUidIsNotFound` | App | 404 | 404 | NOT VERIFIED |
| `missingTenantHeaderFailsClosed` | App | fail‑closed | 400/401 | NOT VERIFIED |
| `unauthenticatedTenantRequestIsRejected` | App | 401 | 401 | NOT VERIFIED |
| `issuesInboxRejectsSpoofedTenantHeader` | App | 403 | 403 | NOT VERIFIED |
| `memberListContainsOnlyOwnTenantRows` | App | own‑only | asserts other‑tenant emails absent | NOT VERIFIED |
| `crossTenantMembershipIdAnswers404NotForbidden` | App | 404 (no leak) | 404 | NOT VERIFIED |
| **DB‑layer RLS cross‑tenant read/write** | **DB** | **deny** | **no such test exists** | **NOT VERIFIED (gap)** |
| Connection‑pool context leak between requests | DB | no leak | `SET LOCAL` is tx‑scoped (design) | NOT VERIFIED |
| Revoked membership loses access | App | deny | membership re‑checked per request (design) | NOT VERIFIED |

**Interpretation:** the **application layer is coherent and has meaningful adversarial tests**, but they were **not run here** and H2 cannot prove Postgres behavior. The **database layer provides no tested (and, per VULN‑1..3, no effective) backstop.** Net: tenant isolation is **PARTIAL**, not proven end‑to‑end.

---

## Deliverable 4 — Database inventory (tenant tables · RLS · policies · roles)

**App DB role:** `jotheca` = Postgres **superuser + owner** (docker‑compose `POSTGRES_USER`; migrations run as it). **No least‑privilege role, no GRANTs, no `NOBYPASSRLS`.** ⇒ RLS not applied to the app (VULN‑1).

**Policy shape (every table):** `ENABLE ROW LEVEL SECURITY` + `USING (app.tenant_id IS NULL OR ='' OR tenant_id=current_setting('app.tenant_id',true))`. **No `WITH CHECK`, no `FORCE`.**

| RLS migration | Tables | RLS enabled | USING | WITH CHECK | FORCE |
|---|---|---|---|---|---|
| V2 | memberships, invitations, audit_logs | yes | fail‑open | no | no |
| V6 | categories, branches, shelf_locations, books, book_copies | yes | fail‑open | no | no |
| V8 | loan_policies, loans, reservations, fines, notifications | yes | fail‑open | no | no |
| V10 | book_interests, purchase_requests | yes | fail‑open | no | no |
| V12 | media_objects, book_ratings, book_reviews, review_reports, review_helpful_votes | yes | fail‑open | no | no |
| V15 | storage_usage, library_subscriptions, **payments** | yes | fail‑open | no | no |
| V17 | book_embeddings, ai_usage_counters, search_events | yes | fail‑open | no | no |
| V21 | issues, issue_messages | yes | fail‑open | no | no |
| V24 | staff_permissions | yes | fail‑open | no | no |

**Non‑tenant / global (correctly excluded):** `users`, `libraries` (the tenant itself), plans. **Possible gap:** `author_profiles` (V22) not in any RLS list — confirm whether it is tenant‑owned. **Note:** migrations live in two trees (`resources/db/migration/*.sql` and `java/db/migration/*.java`) but both land on `classpath:db/migration`, so Flyway runs them all — **verified structurally, not at runtime.**

---

## Deliverable 5 — Files modified / added / deleted (this audit)

- **Modified:** none. **Added:** this report. **Deleted:** none.
- The audited code is the batch already committed on `arena/a775b9a4-j-otheca` @ `50f8426` (rebrand, payment fix, Paystack HMAC, circulation lock, overdue reminders, AI eval harness, audit docs). Full file list is in that commit and in `docs/system-audit-implementation-report.md` §9.6.

---

## Deliverable 6 — Regression tests & actual execution results

| Test | Purpose | Executed here? | Result |
|---|---|---|---|
| `PaystackSignatureTest` | HMAC accept/tamper/wrong‑secret/malformed | No (JVM) — **algorithm re‑verified via Python mirror** | Mirror **11/11 PASS**; JUnit NOT VERIFIED |
| `PaymentWebhookSecurityTest` | forged/missing webhook sig → 401 | No | NOT VERIFIED |
| `OverdueReminderTest` | idempotent overdue reminder | No | NOT VERIFIED |
| `CirculationFlowTest` | issue/renew/return + double‑issue 409 | No | NOT VERIFIED |
| `TenantIsolationTest` | cross‑tenant denial (app layer) | No | NOT VERIFIED |
| `SecurityHardeningTest`, `AuthFlowTest`, `RateLimitTest`, `HardeningTest` | auth/rate/security | No | NOT VERIFIED |
| `AiRetrievalEvaluationTest`, `AiFlowTest` | AI metrics + isolation | No | NOT VERIFIED |
| **PostgreSQL RLS cross‑tenant** | DB‑layer isolation | **Does not exist** | **GAP** |

Java syntax: 8/9 audited files parse cleanly; the 1 "failure" is a pre‑existing nested `record` the Java‑8‑grammar parser can't read (not a defect).

---

## Deliverable 7 — Build / integration / dependency‑scan results

| Check | Ran? | Exit / output | Limitation |
|---|---|---|---|
| `mvn` compile / `test` / `verify` | **No** | — | No `javac` (jdk4py is runtime‑only); Maven Central TLS‑blocked; no dep cache |
| PostgreSQL RLS integration | **No** | — | No Postgres/Docker |
| Flutter `analyze`/`test` | **No** | — | No Dart/Flutter SDK; pub.dev off‑list |
| Dependency vuln scan (Maven/Flutter) | **No** | — | No scanner; vuln DB unreachable → **NOT claimed clean** |
| Secret‑exposure scan | **Yes** (git grep) | clean | only documented dev demo creds + dev‑only length‑guarded JWT secret |

---

## Deliverable 8 — Residual‑risk register (unresolved)

1. **VULN‑1..4 (tenant isolation at the DB layer): CRITICAL/HIGH, unresolved.** Until a least‑privilege role + fail‑closed `WITH CHECK` policies + a Postgres RLS test exist, cross‑tenant protection depends solely on the application layer.
2. **R2/R3 rate‑limiting weaknesses:** unresolved (spoofable limits; unbounded memory).
3. **R4/R5:** low, deferred.
4. **All runtime verification (compile, JUnit, RLS, Flutter, dep scan): NOT VERIFIED** — must run on the Windows toolchain / CI.
5. **Possible RLS coverage gap** (`author_profiles`) — confirm against live schema.

---

## Deliverable 9 — Release‑readiness assessment

**NOT production‑ready.** Per §17, readiness requires demonstrated cross‑tenant blocking at **both** app and DB layers, executed auth/payment/data‑integrity/isolation tests, and completed dependency/deployment review. Current state:

- **Demonstrated (by inspection/design only):** strong app‑layer isolation design with meaningful adversarial tests; hardened Paystack HMAC (algorithm executed); sound auth token hygiene; AI retrieval tenant‑filtered before scoring; no committed secrets.
- **NOT demonstrated:** any runtime test (no toolchain here); **DB‑layer isolation is ineffective (VULN‑1) and untested (VULN‑4)**; dependency scan; deployment/secret rotation review.

**Minimum to reach readiness:** land the VULN‑1..4 RLS remediation + a Postgres RLS cross‑tenant test, resolve R2/R3, then run the full `mvn verify` + CI (Postgres/Redis) + Flutter suites green.

---

## Remediation plan (presented for approval — **no changes made yet**)

**Phase A — Tenant isolation at the DB layer (highest impact; needs your approval because it changes DB roles/migrations):**
1. New Flyway migration: create app role `jotheca_app` (`NOBYPASSRLS`, non‑owner), `GRANT SELECT/INSERT/UPDATE/DELETE` on tenant tables, and switch the runtime datasource to it (migrations keep an admin role). **Impact:** touches deployment config + connection user — I'll detail before applying.
2. Rewrite RLS policies to **fail‑closed** and add **`WITH CHECK`**; consider `FORCE ROW LEVEL SECURITY`.
3. Add a **PostgreSQL RLS cross‑tenant integration test** (Testcontainers/CI) proving read+write denial for the app role.
4. Close any coverage gap (confirm `author_profiles`).

**Phase B — Quick wins (low risk):** R2 (trusted‑proxy XFF), R3 (bounded eviction). R4/R5 optional.

**Phase C — Verification on your side:** `mvn clean verify`, CI Postgres/Redis RLS job, `flutter analyze/test`, dependency scan.

**Approval requested:** Phase A changes DB roles/migrations and deployment config — please confirm scope (and the app‑role name / whether to keep the current superuser for migrations) before I implement. Phases B–C I can start once you confirm. Nothing will be pushed until you approve.
