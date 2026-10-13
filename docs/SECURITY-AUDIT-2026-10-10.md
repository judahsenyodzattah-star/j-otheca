# J'otheca — Security & Correctness Audit (2026-10-10)

_Static source review (this environment cannot run Maven/Flutter — see §6). Findings are
split into **Verified defects**, **Potential risks / hardening gaps**, and **Unverified
assumptions**. **No code was changed.** File:line references are to the current tree
(branch `arena/a775b9a4-j-otheca`)._

Pillars reviewed: authentication, tenant isolation/RLS, permissions, payments, AI.

---

## 1. VERIFIED — Payment provider fails open by default (HIGH, conditional)

**What:** `app.payments.provider` is **never set** in any config, profile, or env example
(`application.yml`, `application-staging.yml`, `infra/.env.staging.example` — confirmed by
grep; only `storage.provider` and mail are configured). Because
`MockPaymentProvider` is declared `@ConditionalOnProperty(name="app.payments.provider",
havingValue="mock", matchIfMissing = true)`, **the mock is active in every profile unless an
operator explicitly sets `provider=paystack` + `PAYSTACK_SECRET_KEY` at deploy time.**

Why that matters:
- `MockPaymentProvider.validSignature(...)` returns `"mock".equals(signatureHeader)` — a
  fixed, guessable "signature."
- `MockPaymentProvider.verify(...)` returns `paid=true` for **any** reference.
- The webhook route `/api/v1/billing/webhooks/**` is `permitAll` (`SecurityConfig`), and
  `BillingController.paystackWebhook` only checks the signature, then calls
  `PaymentService.confirmByReference(reference)`, which trusts `provider.verify()`.

**Reproduction (in a mock/default deployment, no auth):**
```
POST /api/v1/billing/webhooks/paystack
x-paystack-signature: mock
{"event":"charge.success","data":{"reference":"<any existing Payment reference>"}}
```
→ the referenced Payment is marked `PAID`. An attacker can obtain a valid reference by
starting their **own** onboarding checkout (`PaymentService.initiate…` creates a Payment row
with a reference), then self-forge this webhook → **free library onboarding**, or renew a
subscription / add storage / settle a fine without paying. (`confirmByReference` is
idempotent and does a `verify()` call — but the mock's `verify()` always says paid.)

**Blast radius:** any environment on the mock provider — i.e. the **default**, including
staging and any prod that forgets the explicit setting. Correctly-configured
`provider=paystack` deployments are **not** affected (real HMAC-SHA512 signature required).

**Fix direction (needs your approval):** fail **closed** for money —
- remove `matchIfMissing = true` for the payment provider (or gate it to `dev`/`test`
  profiles only), so an unset provider refuses to start rather than silently mocking;
- set `app.payments.provider: paystack` explicitly in `application-staging.yml` + document
  `PAYSTACK_SECRET_KEY` in the staging env example;
- additionally require a non-mock provider (or a valid provider signature) before the
  webhook mutates state.

---

## 2. POTENTIAL RISK — Postgres RLS is a read-path backstop, not a hard boundary (MEDIUM)

Every tenant table gets a policy of the form (`V2__RlsPolicies` and siblings):
```sql
CREATE POLICY <t>_tenant_isolation ON <t>
USING ( current_setting('app.tenant_id', true) IS NULL
        OR current_setting('app.tenant_id', true) = ''
        OR tenant_id = current_setting('app.tenant_id', true) );
```
Two properties weaken RLS as an independent guarantee:
1. **Fail-open when the session var is unset/empty** — if `SET LOCAL app.tenant_id` is ever
   skipped (a new code path that doesn't open a tenant transaction, the
   `TenantAwareDataSource` proxy failing to arm — it only logs a warning on error, or a raw
   JDBC connection bypassing the proxy), **all tenants' rows are visible**. This is
   deliberate (platform analytics, Flyway, seeding run with no tenant), but it means the
   real isolation boundary is the **application layer** (`TenantResolutionFilter` →
   `TenantContext` → Hibernate `tenantFilter` → `TenantGuard`), with RLS as a secondary net.
2. **No `WITH CHECK` clause** — so the policy does not constrain `INSERT`/`UPDATE`
   `tenant_id`. A bug that writes a row under the wrong tenant would not be blocked by RLS.

**Positives confirmed:** coverage is broad — RLS is enabled on ~31 tables
(books, book_copies, loans, fines, reservations, memberships, invitations, issues,
issue_messages, media_objects, book_reviews, book_ratings, review_*, book_interests,
purchase_requests, library_subscriptions, payments, storage_usage, book_embeddings,
ai_usage_counters, search_events, audit_logs, staff_permissions [V24], …). The H2 dev/test
path relies on the Hibernate filter + service guards (RLS is PG-only, by design).

**Fix direction (hardening, optional):** if RLS should be a hard boundary, add
`WITH CHECK (tenant_id = current_setting('app.tenant_id', true))` and use a dedicated DB
role for tenant traffic with a fail-closed default, keeping a separate elevated role for
platform/migration operations. This is a design tradeoff (cross-tenant ops are legitimately
needed), so it's flagged rather than treated as a bug.

---

## 3. POTENTIAL RISK — HMAC webhook signature compared non-constant-time (LOW/MEDIUM)

`PaystackPaymentProvider.validSignature` computes the HMAC-SHA512 hex and compares with
`expected.equalsIgnoreCase(signatureHeader)`. `String.equals*` is **not** constant-time
(CWE-208). Remote timing attacks on an HTTP HMAC compare are impractical (network jitter
dwarfs the delta), but the codebase already treats timing seriously (the R1 login-timing
fix), so this is worth aligning: compare with `MessageDigest.isEqual(expected.getBytes(),
sig.getBytes())`. LOW practical severity; cheap to fix.

---

## 4. POTENTIAL RISK — Rate limiting is per-node by default; key resolution unverified (LOW)

- `RateLimitFilter` limits `/api/v1/auth/{login,register,forgot-password,reset-password,mfa/verify}`
  to 30/min, with backend `app.rate-limit.backend` = `memory` (default) or `redis`. On a
  **multi-instance** deployment the memory backend limits **per node**, so effective limit
  ≈ 30 × instances. Set `redis` for horizontal scale (a `RedisRateLimiter` exists).
- **Unverified:** whether the limiter keys on the true client IP behind your LB/proxy
  (`getRemoteAddr` vs a trusted `X-Forwarded-For`). If it trusts `X-Forwarded-For`
  unvalidated, an attacker rotates the header to bypass; if it ignores it behind a proxy,
  all traffic shares one key (availability). Confirm against your ingress.

Related positive: `LoginGuard` lockout is **durable** (`REQUIRES_NEW`, survives rollback and
restart — explicitly prevents the infinite-retry rollback hole), 10 attempts → 15 min, resets
on success. Unknown-email logins are covered by the IP rate limiter rather than the
per-account counter (nothing to lock).

---

## 5. VERIFIED-SOLID (no action needed) — things I checked and found correct

- **R1 login timing oracle** — `AuthService.login` runs a BCrypt compare against a fixed
  pre-hashed equalizer even for unknown emails; identical generic `BAD_CREDENTIALS`. Sound.
- **Refresh-token rotation + reuse detection** — replaying a rotated token revokes the whole
  family and audits `TOKEN_REUSE_DETECTED`. Sound.
- **MFA** — `typ=mfa` challenge token is rejected by `JwtAuthFilter` (only `typ=access`
  authenticates); `LOGIN_SUCCESS` audited only after the second factor. Sound.
- **Tenant pinning in AI (isolation "layer 9")** — `SemanticSearchService` loads candidates
  via `findByTenantId(tenantId)`, the criteria query filters `tenantId`, and the pgvector
  ANN native query applies `WHERE tenant_id` **before** vector math; no cross-tenant path.
  Sound.
- **Permission model** — `PermissionService.require` gives admins all perms (immutable),
  librarians the default pack + overrides, patrons none; denials collapse to one
  `INSUFFICIENT_LIBRARY_ROLE` (no capability leak). Overrides only touch approved librarians.
  Consistently applied in the services sampled (Circulation, Catalog, Membership).
- **Dev endpoints** — `DevBillingController` is `@Profile("dev")` (absent in prod), so the
  `permitAll` `/api/v1/billing/dev/**` path 404s outside dev.
- **CORS** — `corsSource` uses a configured origin allow-list (`app.cors-allowed-origin-patterns`
  / `CORS_ORIGINS`), never `*`; strict CSP on `/api/**`; frame-options deny; no-referrer.
- **IDOR** — by-id loads in tenant scope are followed by
  `TenantGuard.requireBelongsToCurrentTenant` (cross-tenant → 404, not 403).

---

## 6. Not executed (environment limits) + how to confirm

This sandbox **cannot** run Maven/Flutter or reach package hosts, so nothing here is a
runtime result — it is static analysis. To confirm on Windows:
```powershell
cd backend;    mvn clean verify      # compiles + 151 tests incl. SecurityHardeningTest
cd ..\mobile;  flutter pub get; flutter analyze; flutter test
# RLS sanity on real Postgres (staging): connect as the app role and confirm a tenant
# transaction cannot see another tenant's rows, and that an UNSET app.tenant_id path is
# never reachable from /api/v1/tenant/**.
```

## 7. Recommended priority

1. **Fix §1 (payment fail-open)** — highest impact, small change; needs your go-ahead.
2. Decide on §2 (RLS hardening) — design call; document the app-layer boundary either way.
3. §3 constant-time compare + §4 set `rate-limit.backend=redis` + confirm IP keying — cheap.
4. Several of these touch areas adjacent to the deferred **R2–R5** items; per your standing
   instruction I have **not** started any fixes. Tell me which to implement and I'll make
   minimal, isolated, regression-tested changes (migrations-first; no redesign).
