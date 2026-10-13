# J'otheca — System Audit, Verification, Gap Analysis & Implementation Report

**Date:** 2026-10-10 · **Branch:** `arena/a775b9a4-j-otheca` · **Method:** Inspect → Verify → Classify → Prioritize → Implement → Test → Document.
**Verification caveat (applies to the whole report):** this environment has **no JDK, Maven, Flutter/Dart, Docker, PostgreSQL, Redis or MinIO**, and Maven Central / Gradle / pub.dev are network-blocked (see `docs/access-and-execution-report.md`). Therefore **no build or test was executed here**; "verification" below means **static code inspection + Python structural checks**, and every runtime behavior is explicitly marked **UNVERIFIED (runtime)**. Exact commands to reproduce are in Part 7. Per instruction, **no commit/push** was made — all changes are working-tree edits.

---

## Part 1 — Repository baseline
- **HEAD:** `7b2dd73` (git history resets between turns; working-tree files persist).
- **Working tree:** 356 changed files preserved — the full J'otheca rebrand, the earlier R1/D1–D3 work, and this session's docs/analysis. **Nothing was reset, discarded, or overwritten.**
- **Structure:** backend = Spring Boot 3.5 / Java 21, Maven `pom.xml` (no wrapper), 18 feature packages, 33 controllers, 48 services, 263 main + 18 test `.java`; Flyway 14 `.sql` + 10 Java (RLS) migrations; mobile = Flutter (`sdk: ^3.5.0`), 37 lib + 8 test `.dart`; infra Docker Compose; CI (`.github/workflows/ci.yml`) provisions `pgvector/pgvector:pg16`, `redis:7`, `mailpit`.
- **Toolchain available here:** `python3 3.11.2`, `git 2.39.5` only.
- **Baseline build/test:** **none runnable** (environmental block, not a code defect). Test-suite facts established by inspection: backend tests all use `@ActiveProfiles("dev")` + in-memory H2/memory-storage/mock-payments (**no external services needed**); Flutter tests are pure Dart; **RLS is proven only in CI** (no Testcontainers in `src/test`).

---

## Part 2 — Feature audit matrix
Status legend: IV = Implemented, Not Verified (runtime) · P = Partially Implemented · D = Defective · M = Missing. (Nothing is claimed "IMPLEMENTED AND VERIFIED" because no test could be executed.)

| # | Area | Status | Key evidence | Severity | Next action |
|---|---|---|---|---|---|
| A | Auth & identity | IV | `AuthService` (R1 timing-equalizer, generic `BAD_CREDENTIALS`), `JwtService` (access/mfa/typ), `JwtAuthFilter`, refresh rotation + family reuse-detection, `LoginGuard` durable lockout, MFA TOTP | — | Reproduce suite (§7) |
| B | Multi-tenancy / RLS | IV **+ design gap** | `TenantResolutionFilter` (server-validated header), `TenantContext` fail-closed, `TenantGuard`, Hibernate filter + `SET LOCAL app.tenant_id`; **RLS policy is fail-open & has no `WITH CHECK`** | MED | Harden RLS (needs approval) |
| C | Roles / permissions | IV | `PermissionService` (admin=all immutable; librarian defaults+overrides; patron none; indistinguishable 403), `TenantGuard.requireBelongsToCurrentTenant` (IDOR→404) | — | Reproduce suite |
| D | Onboarding / platform admin | IV | `OnboardingService` request→plan→payment→`PAYMENT_CONFIRMED`→approve→provision→first `LIBRARY_ADMIN` | — | — |
| E | Catalog / copies | IV (inventory **present**) | `book_copies` + `CopyStatus` lifecycle, barcode/accession, taxonomy (categories/branches/shelf-locations) | — | Stocktaking/QR = optional (A) |
| F | Circulation | IV **+ gaps** | status-guarded issue/return/renew; **no optimistic lock/unique index (TOCTOU)**; overdue *derived*; **no `@Scheduled` reminders/auto-fines** | MED | Concurrency + scheduler (approval) |
| G | Reviews / authors / publishing | IV | duplicate-review blocked, moderation, author opt-in, personal library, interest signals, public pages | — | — |
| H | AI discovery | IV **+ eval gap** | tenant-pinned semantic search, recommendations, staff/patron assistant, demand insights, plan-metered, intent-only audit; **no reproducible evaluation** | LOW | Eval harness (enhancement B) |
| I | Billing / payments | **D → FIXED** | provider fail-open (Part 3); idempotent confirm; dev shortcuts `@Profile("dev")` | HIGH (was) | Fixed; verify (§7) |
| J | Media / storage | IV | object storage (memory/minio), plan storage cap, not DB blobs | — | Confirm orphan cleanup |
| K | Analytics / audit | IV | tenant-scoped dashboards, no hardcoded demo values found; pervasive audit | — | — |
| L | Flutter app | IV | session persistence, loop-safe refresh bridge, role-aware routing, deep links, `X-Library-Id` from active library; backend enforces authz | — | Accessibility pass (E) |
| M | DB / migrations / reliability | IV | ordered Flyway, RLS parity migrations, unique constraints, backup/restore scripts | — | Concurrency (F) |
| N | Tests / docs / evidence | P | 18 backend + 8 Flutter test classes exist; RLS in CI only; counts unverified here | — | Reproduce (§7) |

---

## Part 3 — Verified defects

### DEFECT-1 — Payment provider fails open by default (HIGH) — **FIXED**
- **Root cause:** `app.payments.provider` is set nowhere, so `MockPaymentProvider` (`@ConditionalOnProperty(..., matchIfMissing = true)`) is the active provider in **every** profile. Its `verify()` returns *paid* for any reference and its `validSignature()` accepted the literal header `"mock"`, while `/api/v1/billing/webhooks/**` is `permitAll`.
- **Impact:** an **unauthenticated** forged webhook (`x-paystack-signature: mock` + `charge.success` + any existing reference) marked a Payment `PAID` → **free library onboarding / renewals / storage add-ons / fine settlement** in any mock-configured deployment (the default, incl. staging).
- **Reproduction (pre-fix):** `POST /api/v1/billing/webhooks/paystack` header `x-paystack-signature: mock`, body `{"event":"charge.success","data":{"reference":"<existing>"}}` → payment confirmed with no auth and no real charge.
- **Correction (implemented):**
  1. `MockPaymentProvider` now `@Profile({"dev","test"})` → the fake provider is **never** active in staging/prod; those must set `app.payments.provider=paystack` or the context **fails to start** (fail-closed).
  2. `MockPaymentProvider.validSignature(...)` now returns `false` → the permitAll webhook can never authenticate on the mock's behalf (dev confirms via `/api/v1/billing/dev/mark-paid`, which needs no signature).
  3. `application-staging.yml` now sets `app.payments.provider: paystack` (explicit; requires `PAYSTACK_SECRET_KEY`).
- **Regression test added:** `backend/src/test/java/com/jotheca/billing/PaymentWebhookSecurityTest.java` — asserts forged/missing webhook signatures return **401 BAD_SIGNATURE**. (Fails pre-fix: mock sig passed → 404; passes post-fix: 401.)
- **Files:** `MockPaymentProvider.java` (rewritten), `application-staging.yml`, `PaymentWebhookSecurityTest.java` (new).
- **Compatibility:** dev/test and all existing tests unaffected (all `@ActiveProfiles("dev")`, use `mark-paid`, never the webhook). **Deploy note:** production/staging must now set `app.payments.provider=paystack` + `PAYSTACK_SECRET_KEY`; the app no longer silently mocks payments outside dev/test.
- **Verification:** static checks only (brace/token/YAML) — **runtime UNVERIFIED**; run Part 7 §backend.

### Documented, NOT fixed (need your approval — see Part 6)
- **DEFECT-2 (LOW/MED):** `PaystackPaymentProvider.validSignature` uses `equalsIgnoreCase` (non-constant-time, CWE-208). Fix: `MessageDigest.isEqual(...)` on lower-cased bytes.
- **RISK-1 (MED):** Postgres RLS policy is **fail-open** (`app.tenant_id IS NULL OR '' OR match`) and has **no `WITH CHECK`** → RLS is a read-path backstop only when the session var is set; it does not bound writes. The real boundary is the app layer (solid). Hardening needs a design decision (dedicated role / fail-closed default).
- **RISK-2 (MED):** Circulation issue/return is a read-then-write with no optimistic lock/unique index → concurrent double-borrow TOCTOU.
- **GAP-1 (MED):** No `@Scheduled` jobs → no automated overdue reminders or automatic fine accrual (overdue is derived; fines assessed on-demand).

---

## Part 4 — Genuine gaps (essential vs optional)
- **GAP-1 Automated reminders / fine accrual (partial, arguably essential):** add a scheduled sweep (overdue → reminder notification / fine). Small, fits architecture; **proposed, not implemented** (new background capability).
- **RISK-2 Circulation concurrency (essential-ish):** add optimistic locking (`@Version` on `BookCopy`) or a partial unique index on active loans per copy. Needs a migration; **proposed**.
- **RISK-1 RLS hard boundary (hardening):** design decision.
- **Enhancement A — Stocktaking/QR (optional):** barcodes already exist on copies + a `barcodeQr` plan flag; a stocktaking count workflow is **not** present. Evaluate before building.
- **Enhancement B — AI evaluation (optional, academic value):** add a small reproducible dataset measuring Precision@5 / Recall@10 / nDCG@10, grounding, latency, and cross-tenant leakage. **Not** implemented (needs runtime).
- **Enhancement C — RLS/tenant security test suite (optional):** CI already proves RLS on real Postgres; a dedicated local suite would need Docker.
- **Enhancement E — Flutter accessibility/responsive pass (optional).**

---

## Part 5 — Implementation record (this session)
Only **DEFECT-1** was implemented (the one clearly verified, safe, in-scope, non-deferred defect). No R2–R5 area was touched.
- **Created:** `backend/src/test/java/com/jotheca/billing/PaymentWebhookSecurityTest.java`.
- **Modified:** `backend/src/main/java/com/jotheca/billing/service/MockPaymentProvider.java`; `backend/src/main/resources/application-staging.yml`.
- **Behavior change:** payment provider is fail-closed outside dev/test; webhook cannot be forged via the mock.
- **Tests added:** 2 negative webhook tests (401 on forged/missing signature).
- **Actual results:** static structural checks passed (balanced braces/parens, required tokens present, YAML shape valid). **Not executed** — `mvn test` blocked here.
- **Deploy/migration:** staging/prod must set `app.payments.provider=paystack` + `PAYSTACK_SECRET_KEY`. No DB migration required.

---

## Part 6 — Remaining risks & decisions needing your approval
- **R2–R5 deferred security tasks:** untouched, per instruction. None of the fixes above were confirmed to be in that set; DEFECT-1 was treated as an independent, urgent, verified vulnerability and fixed conservatively (config + signature only, no logic/tenant changes). Tell me if any part overlaps a protected area and I will revert.
- **Runtime unverified:** every behavioral claim needs the Part 7 commands.
- **Approvals requested:** implement DEFECT-2 (constant-time HMAC), RISK-1 (RLS `WITH CHECK`/fail-closed), RISK-2 (optimistic locking + migration), GAP-1 (scheduled reminders), and/or Enhancement B (AI eval harness). I recommend **DEFECT-2 + RISK-2** next (small, high-value), then decide on RISK-1/GAP-1.

---

## Part 7 — Final verification
**Executed here:** git/python structural checks only (see Part 5). **Not executed (environmental):** backend compile/tests, Flutter analyze/tests, RLS integration, Docker/services. Reproduce on a normal machine:
```powershell
# Backend (H2 in-memory; no external services): includes the new PaymentWebhookSecurityTest
cd backend
mvn clean verify

# Mobile
cd ..\mobile
flutter pub get
flutter analyze
flutter test

# Real-DB / RLS parity (as CI): Docker + pgvector + Redis, boot staging profile,
# run the cross-tenant assertions in .github/workflows/ci.yml
```

---

## Part 8 — Academic & demonstration readiness
- **Evidence-backed (static, strong):** auth/token/refresh/MFA design; tenant-resolution + permission model; payment lifecycle; AI tenant-pinning; copy-level inventory; the DEFECT-1 fix rationale.
- **Require runtime reproduction before claiming:** the "151 tests pass" style counts, RLS enforcement on real Postgres, and any performance figures — **do not cite these until `mvn verify` / CI is green**.
- **Suggested demo sequence:** (1) patron login → borrow/return at the desk; (2) role enforcement — a librarian without `FINES_WAIVE` is denied; (3) **cross-tenant denial** — a token for library A cannot read library B (404); (4) onboarding → mock mark-paid → super-admin approval → provisioned library + first admin; (5) AI semantic search scoped to one library; (6) the fixed webhook rejects a forged signature (401).

---

_No commit/push performed (working-tree only, per your instruction). This report plus `docs/access-and-execution-report.md`, `docs/SECURITY-AUDIT-2026-10-10.md`, and `docs/SYSTEM-ANALYSIS-2026-10-10.md` form the audit record._

---

# Part 9 — Follow-up pass (2026-10-10): agreed tasks 1–5

## 9.0 Protected-task boundary (R2–R5) — now located and honored
Definitions found in `docs/audits/BUGFIX-REPORT-2026-10-10.md`:
- **R2** — `X-Forwarded-For` trusted unconditionally (rate-limit key + IP logging).
- **R3** — `InMemoryRateLimiter` never evicts idle keys (dev/test default).
- **R4** — `GlobalExceptionHandler` maps constraint / optimistic-lock failures to **500** instead of **409**.
- **R5** — Loan-list N+1 in `CirculationService.toDto` (perf).

Overlap check against the five agreed tasks: **DEFECT-1 (payment fail-open) and Task 1 (Paystack HMAC) do NOT overlap R2–R5.** **Task 2 (circulation locking) DOES collide with R4** (see 9.2) and **Task 3 (RLS) is not in R2–R5** but is design-sensitive. No R2–R5 code was modified.

## 9.1 Task 1 — Paystack HMAC verification — IMPLEMENTED (UNVERIFIED at runtime)
- **Changed:** `backend/.../billing/service/PaystackPaymentProvider.java` — `validSignature` now:
  - computes HMAC-SHA512 over the **exact raw body** with an explicit UTF-8 key (raw-body binding);
  - **constant-time** comparison via `MessageDigest.isEqual` (was `equalsIgnoreCase`, CWE-208);
  - **fails closed** on null/blank/malformed (non-hex / odd-length) signatures instead of throwing.
- **Test added:** `backend/src/test/java/com/jotheca/billing/PaystackSignatureTest.java` — offline (no Spring/network): valid-HMAC accepted; tampered body rejected; wrong-secret rejected; null/blank/malformed rejected.
- **Status:** static checks pass (braces/parens/tokens). **UNVERIFIED** — `mvn` unavailable here.

## 9.2 Task 2 — Circulation concurrency — VERIFIED GAP, IMPLEMENTED via pessimistic lock (UNVERIFIED at runtime)
- **Evidence (code-level):** `CirculationService.issue(...)` is a single `@Transactional` **read → check `copy.status==AVAILABLE` → set `BORROWED` → save** with **no `@Version` on `BookCopy`** and no `@Lock`/unique index. Under READ_COMMITTED, two concurrent issues of the same copy can both pass the check → **double active loan** (TOCTOU).
- **Why not auto-implemented:** the fix you named (`@Version` optimistic locking) throws `OptimisticLockException` on conflict, which **R4 (deferred)** currently maps to **HTTP 500**. Implementing it alone would surface 500s and effectively depend on a protected task. A **pessimistic write lock** (`@Lock(PESSIMISTIC_WRITE)` on the copy fetch) closes the same race and returns the existing clean `409 COPY_NOT_AVAILABLE` **without touching R4 or needing a migration**.
- **Decision (yours):** **(a) pessimistic lock**. **Implemented (UNVERIFIED at runtime):** `BookCopyRepository.findByIdForUpdate(...)` = `@Lock(PESSIMISTIC_WRITE) @Query("select c from BookCopy c where c.id = :id")`; `CirculationService.issue()` resolves the copy, then re-fetches it under the row lock **before** the status checks, so concurrent desk issues of one copy serialize and the loser reads `BORROWED` → the existing clean `409 COPY_NOT_AVAILABLE`. **No migration, no `@Version`, R4 untouched.** The reservation-collection path (calls `createLoan` directly) is already serialized by reservation state and was intentionally left unchanged.

## 9.3 Task 3 — RLS hardening — AUDITED, NOT modified
- **Finding:** RLS is **not** among R2–R5, so it isn't blocked by the protected-task rule. But the current policy is **fail-open** (`app.tenant_id IS NULL/''/match`) with **no `WITH CHECK`**; the app layer is the real boundary. Making RLS a hard boundary requires a **fail-closed default + dedicated role + `WITH CHECK` that still permits platform/seeding writes** — a schema/security design change that **cannot be verified here** (no Postgres/Docker) and risks breaking platform provisioning if done wrong.
- **Action:** documented only. Recommend approving a design (dedicated tenant role; `WITH CHECK`; fail-closed default) before any migration. No policy changed.

## 9.4 Task 4 — Reminders — VERIFIED MISSING, IMPLEMENTED as lazy overdue sweep (UNVERIFIED at runtime)
- **Evidence:** `grep @Scheduled` = **0**; no "due-soon"/"overdue reminder" path. The codebase's established pattern is **lazy sweeps** (reservation pickup expiry, subscription EXPIRED flip — both `REQUIRES_NEW`). Notifications exist for LOAN_ISSUED / FINE_ASSESSED, but **nothing reminds patrons of due/overdue loans**.
- **Decision (yours):** implement the **lazy overdue-reminder sweep**. **Implemented (UNVERIFIED at runtime):** `Loan.reminderSentAt` + Flyway **V25** `ALTER TABLE loans ADD COLUMN reminder_sent_at TIMESTAMP NULL`; `LoanRepository.findByTenantIdAndStatusAndDueAtBeforeAndReminderSentAtIsNull(...)`; `CirculationService.sendOverdueReminders()` = `REQUIRES_NEW` sweep (mirrors `expireOverdueReservations`) that notifies the borrower once (`LOAN_OVERDUE`) and stamps `reminder_sent_at` (idempotent), invoked lazily at the top of `myActiveLoans()`. New `AuditAction.LOAN_OVERDUE_REMINDER`. No `@Scheduled` subsystem added.

## 9.5 Task 5 — AI evaluation harness — GAP CONFIRMED, HARNESS ADDED (UNVERIFIED)
- **Gap confirmed:** no IR metrics anywhere; `AiFlowTest` only asserts exact ranking for a couple of queries.
- **Added:** `backend/src/test/java/com/jotheca/ai/AiRetrievalEvaluationTest.java` — seeds a small, topically-separated labeled catalog in the Ashesi tenant, runs labeled queries through the real `/api/v1/tenant/ai/search`, and **computes + prints Precision@5, Recall@10, nDCG@10** (deterministic local embedder ⇒ reproducible). Asserts only robust invariants (relevant book in top-3; #1 for the most specific query). Cross-tenant retrieval isolation is asserted by the RLS/isolation suite + `AiFlowTest`, not duplicated here.
- **Status:** static checks pass. **UNVERIFIED** — not executed here.

## 9.6 Files changed this pass (working tree; no commit)
- Modified: `backend/src/main/java/com/jotheca/billing/service/PaystackPaymentProvider.java`.
- Modified (Task 2): `backend/src/main/java/com/jotheca/catalog/repo/BookCopyRepository.java`, `backend/src/main/java/com/jotheca/circulation/service/CirculationService.java` (issue lock + reminder sweep + hook).
- Modified (Task 4): `backend/src/main/java/com/jotheca/circulation/model/Loan.java`, `backend/src/main/java/com/jotheca/circulation/repo/LoanRepository.java`, `backend/src/main/java/com/jotheca/audit/model/AuditAction.java`.
- Created: `backend/src/main/resources/db/migration/V25__loan_overdue_reminders.sql`; tests `backend/src/test/java/com/jotheca/billing/PaystackSignatureTest.java`, `backend/src/test/java/com/jotheca/ai/AiRetrievalEvaluationTest.java`, `backend/src/test/java/com/jotheca/circulation/OverdueReminderTest.java`.
- (Provisionally accepted earlier: `MockPaymentProvider.java`, `application-staging.yml`, `PaymentWebhookSecurityTest.java`.)

## 9.7 Verification commands (Windows) — reproduce locally
```powershell
# New / affected unit tests (offline; no services required)
cd backend
mvn -Dtest=PaystackSignatureTest test
mvn -Dtest=PaymentWebhookSecurityTest test
mvn -Dtest=AiRetrievalEvaluationTest test
mvn -Dtest=OverdueReminderTest test
mvn -Dtest=CirculationFlowTest test        # guards issue() happy path + double-issue 409 (lock regression)

# Full backend suite (H2 in-memory; no external services)
mvn clean verify

# Flutter
cd ..\mobile
flutter pub get
flutter analyze
flutter test

# PostgreSQL RLS / integration parity (as CI; requires Docker + pgvector + Redis)
docker compose -f infra/docker-compose.yml up -d
# then boot the staging profile against jdbc:postgresql://localhost:5432/jotheca and run the
# cross-tenant RLS assertions in .github/workflows/ci.yml
```

## 9.8 Decisions & status
1. **Circulation locking — RESOLVED (pessimistic):** implemented; no migration, R4 untouched.
2. **RLS hardening — STILL OPEN (Task 3):** approve a fail-closed/dedicated-role design before I touch policies. Audit-only so far.
3. **Reminders — RESOLVED (lazy sweep):** implemented (idempotent, lazy on member loan read).
4. **Deferred R2–R5:** left untouched; tell me if you want any of them scheduled.
- **All runtime results remain UNVERIFIED** (no JVM/Maven/Flutter/Docker in this workspace). Run §9.7 to reproduce.

## 9.9 In-sandbox self-testing (2026-10-10) — what could and could NOT be executed
The agent environment has **no Java compiler and no dependency feed** (reasons below), so the JUnit suite could not run here. What WAS executed and passed:

- **Paystack HMAC algorithm + all test vectors — VERIFIED (executed).** A faithful Python mirror of `PaystackPaymentProvider.validSignature` (HMAC-SHA512 over the exact raw body; `HexFormat.parseHex` fail-closed semantics; constant-time compare) was run against the exact `PaystackSignatureTest` vectors: **11/11 PASS** — valid HMAC accepted (incl. uppercase-hex via the `toLowerCase` path); tampered / wrong-secret rejected; null / blank / non-hex / odd-length / null-body all fail closed. The test computes its own valid signature with standard RFC-2104 HMAC-SHA512 lowercase hex (identical to `javax.crypto` + `HexFormat`), so it should pass on the Windows run **provided it compiles**.
- **Java syntax — 8/9 files PARSE-OK** (parser `javalang`). The 9th, `PaystackPaymentProvider`, trips the parser only on a **pre-existing nested `record`** (valid Java 21 the Java-8-grammar parser cannot read) — not a defect; my edits are elsewhere in that file.
- **Symbol / schema resolution audit — PASS.** Every new symbol resolves: `BookCopyRepository.findByIdForUpdate`; `LoanRepository.findByTenantIdAndStatusAndDueAtBeforeAndReminderSentAtIsNull`; `Loan.getUserId/getDueAt/getReminderSentAt/setReminderSentAt`; `Book.getTitle`; `AuditService.log(AuditAction,String,String,Map)`; `AuditAction.LOAN_OVERDUE_REMINDER`; `NotificationService.notify(...)`; `TenantContext.requireLibraryId()`. Raw-SQL columns used by `OverdueReminderTest` all exist: `notifications.type`, `notifications.user_id`, `memberships.user_id`, `loans.due_at`, `loans.reminder_sent_at`.

**Could NOT be executed here (exact reasons):**
- **Compilation + JUnit (`mvn test`):** no `javac` — the only obtainable JDK (PyPI `jdk4py`) is a **runtime** (`jdk.compiler` absent), and a full JDK is not fetchable (Adoptium/Maven are **off the sandbox allow-list — TLS handshake killed at EOF**). Even with a compiler, Spring/Hibernate/JUnit/H2 jars are unfetchable (**Maven Central TLS-blocked; no `~/.m2` cache**).
- **RLS / Postgres integration:** no Docker, no `psql`, no local Postgres.
- **Flutter (`analyze`/`test`):** no Flutter/Dart SDK; pub.dev off allow-list.
- **Concurrency race (pessimistic lock):** structurally correct (code/schema-audited) but a real race needs a live DB + threads; the H2 suite only proves the happy path + double-issue 409.

**Bottom line:** algorithm + syntax + symbol/schema consistency are verified in-sandbox; **compile + the full test suite remain UNVERIFIED and must run on the Windows toolchain** (§9.7). Nothing above should be read as "the JUnit suite passed."
