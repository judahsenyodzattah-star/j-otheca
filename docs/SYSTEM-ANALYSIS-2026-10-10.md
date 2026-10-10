# J'otheca — Full System Analysis (2026-10-10)

_A code-grounded walkthrough of what the platform is, how it works, how everyone logs
in, the role/hierarchy model, and every feature and procedure. Backend = Spring Boot
3.5 / Java 21 (`com.jotheca.*`, 18 feature packages, 33 controllers, 48 services);
client = Flutter (`mobile/`); data = PostgreSQL with Row-Level Security + Flyway
migrations (V1–V24) + MinIO object storage; payments = Paystack (+ a mock provider)._

---

## 1. What J'otheca is

A **multi-tenant SaaS platform for library management with AI-assisted discovery**. Many
independent libraries (universities, schools, public, private, corporate, research, and
even *personal* author libraries) live inside one deployment. Every library's data is
isolated from every other's — enforced in the **database**, not just the UI. The same
human account can be a patron in one library, a librarian in another, and (separately) a
platform super-admin. The tagline in the code: *"many libraries, one platform, isolation
you can test."*

Monorepo layout:
- `backend/` — Spring Boot API, Flyway migrations, static demo web mirror.
- `mobile/` — Flutter app (Android/iOS/Web/Windows) — the primary client.
- `infra/` — Docker Compose (Postgres + MinIO + backend), env examples.
- `.github/workflows/ci.yml` — Postgres + `mvn verify` + live smoke test.
- `docs/` — architecture, ADRs, audits, ops runbooks, academic pack.

---

## 2. Architecture at a glance

```
Flutter app (mobile/)  ──HTTPS/JSON──►  Spring Boot API (backend/)
   Dio + refresh bridge                    │
   GoRouter (role-aware)                   ├─ Spring Security filter chain
   Riverpod session state                  │    RateLimit → JWT → TenantResolution
        │                                  │
        │  Authorization: Bearer <JWT>     ├─ Services (TenantGuard / PermissionService)
        └─ X-Library-Id: LIB-XXXX  ───────►│        │
                                           │        └─ Hibernate tenantFilter + JPA
                                           └─ PostgreSQL (RLS via app.tenant_id)
                                              + MinIO (media) + Paystack (payments)
```

Key design choices visible in code:
- **Stateless API.** No server session cookie; CSRF disabled deliberately (documented as
  returning only if cookie transport is ever added). `SessionCreationPolicy.STATELESS`.
- **Two token types.** Short-lived **JWT access token** (15 min, `typ=access`) + opaque,
  **hashed-at-rest refresh token** (14 days) backed by an `AuthSession` row so it can be
  rotated and revoked. A separate `typ=mfa` challenge token (5 min) is *rejected* by the
  auth filter, so it can never act as a session.
- **Data-driven plan limits.** Subscription tiers and their caps (storage GB, max members,
  max books, feature flags) live in `subscription_plans.limits` JSON — never hard-coded.

---

## 3. The identity model — three orthogonal axes

The system deliberately separates *who you are on the platform*, *what you are in a
library*, and *what kind of member you are*. These never collapse into one "role".

**(a) `GlobalRole` — platform level** (`identity/model/GlobalRole.java`)
| Value | Meaning |
|---|---|
| `USER` | Ordinary account. |
| `SUPER_ADMIN` | Platform operator. Approves new libraries, sees platform analytics. **Never** carries library authority (the JWT deliberately holds no tenant role). |

**(b) `LibraryRole` — per-library, stored on each `Membership`** (`membership/model/LibraryRole.java`)
| Value | Meaning |
|---|---|
| `LIBRARY_ADMIN` | Owns/configures the library. Implicitly holds **every** permission; cannot be locked out or overridden. |
| `LIBRARIAN` | Staff. Gets a **default permission pack**, tunable per-member by an admin. |
| `PATRON` | Ordinary borrowing member. No staff permissions. |

**(c) `MembershipType` — what kind of member** (`membership/model/MembershipType.java`)
`STUDENT, STAFF, FACULTY, RESEARCHER, ALUMNI, PUBLIC_MEMBER, GUEST, AUTHOR` — orthogonal to
permissions; it's descriptive metadata about the person, not an authority.

Supporting enums:
- `UserStatus`: `ACTIVE / SUSPENDED / DELETED` (account level).
- `MembershipStatus`: `PENDING / APPROVED / REJECTED / SUSPENDED / EXPIRED` (per library).
- `LibraryStatus`: `PENDING_APPROVAL / ACTIVE / SUSPENDED`.
- `LibraryType`: `UNIVERSITY / SCHOOL / PUBLIC / PRIVATE / CORPORATE / RESEARCH / PERSONAL`.
- `JoinPolicy`: `PUBLIC / APPROVAL_REQUIRED / STUDENT_ONLY / STAFF_ONLY / INVITATION_ONLY / DOMAIN_VERIFIED / CUSTOM`.
- `CopyStatus`: `AVAILABLE / BORROWED / RESERVED / LOST / DAMAGED / MAINTENANCE / REMOVED`.

---

## 4. The security spine — "9 isolation layers"

The code labels its defense-in-depth explicitly (grep: *"isolation layer N of 9"*). Every
layer fails **closed**.

1. **JwtAuthFilter** — validates the access token; authenticates the **global** identity
   only. The JWT carries **no** tenant authority.
2. **TenantResolutionFilter** — for `/api/v1/tenant/**`, reads `X-Library-Id`, then
   **server-side validates**: library exists, is `ACTIVE`, and the caller holds an
   `APPROVED`, non-expired `Membership` in it. The header is *never trusted alone*.
3. **TenantGuard** — role + tenant assertions used by every tenant-scoped service
   (`requireRole(...)`, `requireBelongsToCurrentTenant(...)`).
4. **TenantContext** — the resolved tenant for the request (a `ThreadLocal`), set **only**
   by the filter; cleared in a `finally`. Tenant work without a context throws
   `TENANT_REQUIRED`.
5. **TenantScopedEntity** — base class; every tenant-owned row carries `tenant_id`.
6. **TenantAwareDataSource + Postgres RLS** — when a transaction begins under a resolved
   tenant, the connection runs `SET LOCAL app.tenant_id = '<id>'`, so the RLS policies
   (migration `V2__RlsPolicies` and friends) apply for that transaction only. (No-ops on
   H2 in dev/tests.)
7. *(By-id IDOR guard)* — direct id lookups bypass Hibernate filters, so **every** by-id
   load in tenant scope is followed by `TenantGuard.requireBelongsToCurrentTenant(...)`;
   cross-tenant access answers **404**, not 403, so existence isn't leaked.
8. *(Error invisibility)* — insufficient-role and missing-permission both return the same
   `403 INSUFFICIENT_LIBRARY_ROLE`, so denials never reveal what a tenant's staff can do.
9. **Tenant-pinned AI candidates** — the semantic-search ANN candidate set is always
   tenant-pinned, so vector search can't reach a foreign tenant's rows.

**Filter order** (`SecurityConfig`): `RateLimitFilter → JwtAuthFilter → TenantResolutionFilter`,
all inside the Spring Security chain (double-registration disabled).

**Public surface (permitAll, enumerated exactly):** `/api/v1/auth/{register,login,refresh,
logout,verify-email,forgot-password,reset-password,mfa/verify}`, `/api/v1/join/**`,
`/api/v1/billing/webhooks/**`, `/api/v1/billing/dev/**`, `/api/v1/public/**`, and
`GET /api/v1/libraries[/{…}]`, `/api/v1/plans`. Everything else requires a token;
`/api/v1/platform/**` requires `ROLE_SUPER_ADMIN`.

**Hardening headers:** `frame-options: deny`, strict CSP (`default-src 'none'`) on `/api/**`,
`referrer-policy: no-referrer`, locked-down `Permissions-Policy`. CORS uses a configured
origin allow-list (`app.cors-allowed-origin-patterns` / `CORS_ORIGINS`) — never `*`.

---

## 5. How everybody logs in (authentication)

`AuthController` → `AuthService`. The full lifecycle:

1. **Register** `POST /api/v1/auth/register` — email + full name + phone + password
   (BCrypt). Rejects duplicate email (`EMAIL_TAKEN`). Issues an email-verification token
   (24 h) and mails a link. In the `dev` profile the raw token is returned in the response
   (no real SMTP). Audited as `REGISTER`.
2. **Verify email** `POST /api/v1/auth/verify-email` — consumes the token, sets
   `emailVerifiedAt`.
3. **Login** `POST /api/v1/auth/login` — see the exact sequence below.
4. **(Optional) MFA** `POST /api/v1/auth/mfa/verify` — exchange the challenge token + a
   6-digit TOTP code for real tokens.
5. **Refresh** `POST /api/v1/auth/refresh` — rotate the refresh token (with **reuse
   detection**, see §5.2).
6. **Logout** `POST /api/v1/auth/logout` — revoke the presented refresh token's session.

### 5.1 The login sequence (and the R1 timing hardening)
`AuthService.login`:
1. Normalize email; look up the user (may be `null`).
2. If the account exists and is **locked** (`LoginGuard`), return `423 ACCOUNT_LOCKED`
   with `retryAfterSeconds`. Failed attempts are counted with a durable lockout.
3. **Credential check — constant-work (R1):** even when the account **doesn't exist**, a
   BCrypt comparison is still run against a fixed pre-hashed *timing-equalizer* string
   (`timingHash`, hashed once in the constructor at the same cost as real passwords). The
   result is ignored and never accepted. This removes the **account-enumeration timing
   oracle** — an unknown email costs the same as a wrong password, with no extra DB query.
4. On bad password: record failure, audit `LOGIN_FAILED` with `reason=bad_credentials`,
   and return `401 BAD_CREDENTIALS` — a single generic message that never reveals which
   part failed.
5. If `status != ACTIVE` → `401 ACCOUNT_INACTIVE`.
6. If **MFA is enabled** → return an `MfaChallengeResponse` (5-min challenge token); **no**
   session tokens yet. `LOGIN_SUCCESS` is audited only after the second factor verifies.
7. Otherwise issue tokens and audit `LOGIN_SUCCESS`.

`issueTokens` creates an `AuthSession` (opaque refresh token stored as a SHA-256 hash,
with a token *family*, device label from the User-Agent, and IP), then mints the JWT
(subject=userId, `email`, `role`=global role, `sid`=session id) and returns
`{accessToken, tokenType=Bearer, expiresInSeconds, refreshToken, user}`.

### 5.2 Refresh rotation + theft detection
`refresh` looks up the session by token hash. If the presented token was **already
revoked** (i.e., replayed after rotation), it treats it as theft: **revokes the entire
token family**, audits `TOKEN_REUSE_DETECTED`, and returns `401 TOKEN_REUSE_DETECTED`.
Otherwise it rotates: issues a new pair, marks the old session revoked and links it to the
replacement. `resetPassword` and account-security actions revoke sessions to force re-login.

### 5.3 Password reset & account security
- **Forgot/reset password** — single-use tokens (60 min); forgot-password **always**
  succeeds (never discloses whether the account exists); reset revokes **all** sessions.
- **`/api/v1/me/security`** (`AccountSecurityController`) — list active **sessions**
  ("this device"), revoke one, revoke all, and **MFA** enroll/enable/disable (TOTP).
- **Rate limiting** — `RateLimitFilter` with pluggable limiter (in-memory or Redis) in
  front of the auth endpoints.

---

## 6. How tenant context is established (per request)

On any `/api/v1/tenant/**` call the client sends `X-Library-Id: <library-UID>` (e.g.
`LIB-PU2026`). `TenantResolutionFilter` then: requires the header → requires an
authenticated `AuthUser` → loads the library by UID (`404 TENANT_NOT_FOUND` if absent) →
requires `LibraryStatus.ACTIVE` (`403 TENANT_INACTIVE`) → requires an `APPROVED`,
non-expired `Membership` for this user (`403 NO_MEMBERSHIP` / `403 MEMBERSHIP_EXPIRED`) →
sets `TenantContext.Current{libraryId, libraryUid, membershipId, role}` → arms the
Hibernate `tenantFilter`. All of this is **server-side**; the header only *names* the
library, it never grants access. `finally { TenantContext.clear(); }`.

---

## 7. Authorization — roles + granular permissions

Two complementary guards:
- **`TenantGuard.requireRole(LIBRARY_ADMIN, LIBRARIAN, …)`** — coarse role gate.
- **`PermissionService.require(LibraryPerm …)`** — fine-grained gate. Resolution order:
  - `LIBRARY_ADMIN` → **all** permissions, immutable (a tenant can never lock out its own
    admin).
  - `LIBRARIAN` → the **default pack** (`LibraryPerm.librarianDefaults()`), adjusted by
    per-member override rows (`StaffPermission`).
  - `PATRON` → none.
  - Denial is a single legacy `403 INSUFFICIENT_LIBRARY_ROLE` for everyone without access
    (§4 layer 8). Clients discover capabilities via `GET /permissions/me`.

**The 10 granular permissions** (`LibraryPerm`, `librarianDefault` in parentheses):
| Permission | Covers | Librarian default? |
|---|---|---|
| `CATALOG_MANAGE` | Books & copies | no |
| `CIRCULATION_OPS` | Issue/return/renew/holds shelf | yes |
| `FINES_MANAGE` | Assess & settle fines at desk | yes |
| `FINES_WAIVE` | Waive fines (money decision) | no |
| `MEMBERS_MANAGE` | Approve/reject/suspend/invite members | no |
| `POLICY_MANAGE` | Loan-policy editor | no |
| `REVIEWS_MODERATE` | Review/rating moderation | yes |
| `ISSUES_HANDLE` | Support inbox | yes |
| `REPORTS_VIEW` | Dashboard/analytics/demand | yes |
| `BILLING_MANAGE` | Plan, renewals, invoices | no |

Admins edit a librarian's overrides via `PermissionService.setOverride` (grant / revoke /
reset-to-default); overrides only ever apply to **approved librarians**, never to admins.

---

## 8. Role hierarchy & what each can actually do

**SUPER_ADMIN (platform, global):**
- Approve/reject library onboarding requests (after payment is confirmed) → provisions the
  tenant and its first `LIBRARY_ADMIN`.
- Platform analytics dashboard (`/api/v1/platform/analytics/dashboard`).
- Note: a super-admin with **no** library membership is routed by the client to a
  "platform console" note — they use the web console, not tenant UX.

**LIBRARY_ADMIN (one library):**
- Everything a librarian can do, **plus** all non-default permissions.
- Manage members: approve/reject/suspend, appoint/demote **librarians** (`changeRole`;
  admin→admin transfer is out of scope, and you can't change your own role or demote an
  admin), tune librarian permission overrides.
- Manage loan policy, review policy, taxonomy, billing/subscription, invitations.
- Configure the library (join policy, branding, allowed domain, anonymous-review toggle).

**LIBRARIAN (staff):**
- Run the circulation desk, handle the support inbox, moderate reviews, view reports —
  scoped to the default pack + any admin overrides. Money-writing or member-management
  actions are **off** by default and must be explicitly granted.

**PATRON (member):**
- Browse/search the catalog, borrow/return/renew, reserve, see and pay their own fines,
  rate/review books, open support issues, mark interest in books, receive notifications.

**AUTHOR (an opt-in capability on any account, not a separate role):**
- Activate an author profile + selling, run the author studio, publish a **PERSONAL**
  library with their own books for sale, get interest signals, and a public author page.

---

## 9. Library lifecycle (onboarding → provisioning)

`OnboardingService` (spec §11): **request → plan → payment → PAYMENT_CONFIRMED →
SUPER_ADMIN approve → tenant provisioned.**
1. Applicant (any signed-in user) submits `CreateOnboardingRequest` (name, type, country,
   city, address, description, plan code). Audited `ONBOARDING_SUBMITTED`.
2. A `SubscriptionPlan` is resolved; `PaymentService.initiateSubscriptionPayment` returns
   checkout details (reference, authorization URL, amount, currency, provider).
3. Payment is confirmed (Paystack webhook or the dev "mark-paid" endpoint).
4. **SUPER_ADMIN approves** (`OnboardingService.approve`): refuses if already approved or
   payment not confirmed; then
   - creates + activates the `Library` (permanent UID generated),
   - makes the **applicant the first `LIBRARY_ADMIN`** (`MembershipType.STAFF`, `APPROVED`),
   - creates the subscription, marks the request `APPROVED`.
   (`reject` sets the request `REJECTED`.)

A library's `JoinPolicy` then governs how *others* get in (§10).

---

## 10. Membership lifecycle (joining & staff management)

**Joining** (`JoinController` → `MembershipService.join`, public endpoint): an authenticated
user requests to join a library by slug. The **policy engine** (`decideOutcome`) decides:
- `PUBLIC` → `APPROVED` immediately (type `PUBLIC_MEMBER`).
- `APPROVAL_REQUIRED` / `CUSTOM` → `PENDING` (a librarian approves later).
- `STUDENT_ONLY` → requires `studentId` in the payload → `PENDING` (type `STUDENT`).
- `STAFF_ONLY` → requires `employeeId` → `PENDING` (type `STAFF`).
- `DOMAIN_VERIFIED` → email must end with the library's `allowedDomain` → `APPROVED`
  (type `STUDENT`), else `403 DOMAIN_NOT_ALLOWED`.
- `INVITATION_ONLY` → requires a valid invitation code/slug **belonging to that library**
  (IDOR-checked) → `APPROVED` if the invitation auto-approves, else `PENDING`.

Everyone who joins starts as `PATRON`. Re-applying after `REJECTED/SUSPENDED/EXPIRED`
reuses the row (preserves history + the unique constraint). Joining is audited
(`MEMBERSHIP_JOINED` / `MEMBERSHIP_REQUESTED`).

**Staff management** (tenant plane, guarded by `MEMBERS_MANAGE` / admin):
- `approve` / `reject` — only from `PENDING`; approval checks **member capacity** against
  the plan (`EntitlementService.requireMemberCapacity`) and assigns a membership number.
- `suspend` — only from `APPROVED`; decrements the library's member count.
- `changeRole` — admin-only; appoint/demote between `LIBRARIAN` and `PATRON` (staff
  capacity checked when promoting to librarian).
- **Invitations** (`TenantInvitationController`) — create/list/deactivate invite codes or
  slugs with a membership type and an auto-approve flag.
- **Self-service** (`/api/v1/me/memberships`, `myCard`) — a user sees all their
  memberships across libraries.

---

## 11. Feature modules (what the app does)

**Catalog** (`TenantCatalog/Copy/TaxonomyController`, staff `CATALOG_MANAGE`): books CRUD;
physical **copies** with a `CopyStatus` lifecycle (never hard-deleted — removal is a status,
preserving loan history); taxonomy = categories, branches, shelf-locations.

**Circulation** (`TenantCirculation/Fine/Reservation/NotificationController`): per-library
**loan policy** (`POLICY_MANAGE`); issue/return/renew loans (`CIRCULATION_OPS`); member
lookup; **reservations/holds** with a ready→collect flow (gated by plan); **fines** —
assess, settle at desk, waive (`FINES_WAIVE`), pay online; **notifications** (in-app feed +
unread count + mark read).

**Reviews & ratings** (`TenantReviewController`): members rate (1–5) and review books;
per-library **anonymous-review** toggle; moderation queue (approve/reject, report/dismiss,
helpful votes) under `REVIEWS_MODERATE`; per-library review policy.

**Media** (`TenantMediaController` + `PublicMediaController`): multipart uploads for book
covers, library logo/banner, profile avatars → MinIO (or in-memory storage in dev);
storage is **hard-capped by the plan** (`EntitlementService` storage guard); public read
for published media.

**Issues / support** (`TenantIssueController`): members open support tickets; staff run an
**inbox** with filters (status, category, assignee) and actions — assign, set priority,
request-info, escalate, resolve, reopen. **Conversation-driven transitions**: a reporter
replying on a `NEEDS_INFO` ticket hands it back to the team; replying on a `RESOLVED`
ticket reopens it. Opening an issue notifies staff.

**Publishing & authors** (`TenantPublishing/PublicCatalog/PublicAuthor/AuthorProfileController`):
opt-in **author profile** (activate/deactivate selling); **author studio**; a PERSONAL
library where an author lists their own books (some for sale, priced in GH₵); **interest
signals** — a member marking interest in a book notifies the author (`INTEREST_SIGNAL`);
**public** author pages (by slug) and public book pages (deep-linkable, no sign-in).

**AI-assisted discovery** (`TenantAiController`, Phase 10 — all calls **plan-metered** by
`AiQuotaService` and audited as *intent + length only*, never the question text):
- **Semantic search** — embeddings via a local hash embedder or OpenAI; the ANN candidate
  set is always tenant-pinned (layer 9).
- **Recommendations** for members.
- **Staff assistant** (`AssistantService`) — intent-classified: operational metrics,
  demand/acquisition questions, and free-form questions that fall back to tenant-filtered
  semantic search; can also **draft** a support issue (classify + draft verbatim text).
- **Patron assistant** — same metering/hygiene.
- **Demand insights** and **usage/embeddings-rebuild** endpoints.

**Analytics** (`TenantAnalyticsController`, `PlatformAnalyticsController`): tenant
dashboard + `me` view (`REPORTS_VIEW`); platform-wide dashboard (`SUPER_ADMIN`).

**Audit** (`AuditService`): every security- and state-relevant action is recorded with
actor, tenant (when armed), target, and metadata (e.g., `LOGIN_SUCCESS`, `ROLE_CHANGED`,
`TOKEN_REUSE_DETECTED`, `MEMBERSHIP_APPROVED`, `ONBOARDING_SUBMITTED`).

**Billing** (`TenantBilling/Billing/DevBillingController`): plans (`STARTER`,
`PROFESSIONAL`, `ENTERPRISE` — data-driven limits), view subscription, renew, buy a
**storage add-on** (limits = plan + add-on, both data), payment history; Paystack
**webhook** (signature-verified) plus a dev "mark-paid" path. Plan features gate behavior
(e.g., reservations, storage, AI quota).

**Platform admin** (`PlatformController`, `ROLE_SUPER_ADMIN`): library approval workflow,
platform stats/analytics, plan changes.

---

## 12. The Flutter client (how the UI maps to all this)

- **State**: Riverpod `SessionController` (`session.dart`) holds `accessToken`,
  `refreshToken`, `user`, `activeLibraryUid`, and `memberships`. `isSignedIn`,
  `isSuperAdmin`, `approved` (drives the "which library can I act in" picker), and
  `activeRole` are derived. Persisted to local storage across launches.
- **Active library**: `switchLibrary(uid)` sets `activeLibraryUid`; each feature screen
  attaches `X-Library-Id: session.activeLibraryUid` to its requests. So "switching
  library" in the UI is exactly what re-points the tenant header the server validates.
- **Token refresh** (`api_client.dart`): a Dio interceptor catches a 401 on a normal
  request, performs a **one-shot refresh** through a bridge installed once at startup
  (`refreshBridge`), and retries with the rotated token; failure triggers `expiredBridge`
  (sign-out). Loop-safe (the refresh call itself is never retried).
- **Routing** (`router.dart`, GoRouter): role-aware redirects. Signed-out → `/login`.
  Public deep links work without auth: `/join/:key` (invitations/QR, spec §14) and
  `/book/:librarySlug/:bookSlug` (public book pages, spec §24). A super-admin with no
  membership is sent to `/platform-note`. Screens: discover, home, catalog + book detail,
  **desk** (staff circulation), **manage** (library admin), issues (mine + staff inbox),
  AI, author studio/profile + public author page, settings (sessions, MFA, invoices),
  onboarding (register a library), public book page.

---

## 13. The demo/seed data (concrete roles in practice)

`DevDataSeeder` (dev profile) creates a ready-made cast:
- `admin@jotheca.app` — **SUPER_ADMIN** (Platform Owner).
- `libadmin@jotheca.app` — **LIBRARY_ADMIN** of *Pentecost University Library*.
- `staff@jotheca.app` — **LIBRARIAN** at Pentecost.
- `accraadmin@jotheca.app` — **LIBRARY_ADMIN** of *Accra Central Public Library*.
- `patron@jotheca.app` — **PATRON** (approved at Accra Central; **pending** student request
  at Pentecost to demo the approval queue).
- `author@jotheca.app` — **AUTHOR/LIBRARY_ADMIN** of a PERSONAL library *"Auntie Esi
  Writes"* with a book for sale (GH₵350).
- *Ashesi University Library* is seeded `PENDING_APPROVAL`-style with an invitation code
  (auto-approve) to demo invitation-only joining; Accra Central allows anonymous reviews.

---

## 14. End-to-end journeys

**A patron borrows a book.** Signs in → picks their active library (`X-Library-Id`) →
browses/searches catalog (optionally semantic) → a librarian at the **desk** looks up the
member and issues the copy (`CIRCULATION_OPS`) → copy becomes `BORROWED`, a loan + due
date from the loan policy → patron sees it under "my loans", can renew or reserve →
return flips the copy to `AVAILABLE`; overdue produces a fine the patron can view/pay.
Every step is tenant-scoped and audited.

**A library comes to life.** A user registers a library (onboarding) → pays for a plan →
a **super-admin** approves it → the tenant + first **LIBRARY_ADMIN** are provisioned → the
admin sets the join policy, loan policy, and branding, invites/appoints **librarians** and
tunes their permissions → patrons join per policy and start borrowing.

**An author sells a book.** Any user activates an author profile → lists books in a
PERSONAL library, some for sale → a public author page + public book pages are deep-linkable
→ interested members signal interest, notifying the author → the author runs their studio.

**Platform oversight.** A super-admin reviews pending onboarding + payment, approves or
rejects, watches the platform analytics dashboard — all under `/api/v1/platform/**`.

---

## 15. Cross-cutting concerns

- **Error contract**: every error is a structured `ApiError{status, code, message}`;
  codes are stable and deliberately non-revealing (e.g., generic `BAD_CREDENTIALS`,
  indistinguishable `INSUFFICIENT_LIBRARY_ROLE`, `404` for cross-tenant ids).
- **Auditing** is pervasive and tenant-aware where a tenant is armed.
- **RLS migrations** (`V2__RlsPolicies` and later) enforce isolation at the DB layer on
  real Postgres; H2 (dev/tests) relies on the Hibernate `tenantFilter` + service guards.
- **Plan entitlements** (`EntitlementService`) hard-check member count, staff count, media
  storage, and feature availability (e.g., reservations) against data-driven limits.
- **API versioning & conventions**: everything under `/api/v1`; tenant plane `/tenant/**`,
  platform plane `/platform/**`, public plane `/public/**`, self plane `/me/**`.

---

## 16. Notable engineering properties (why the tests are shaped this way)

- **Isolation is tested, not just asserted** — e.g., `TenantIsolationTest` and a 33-case
  negative suite exercise cross-tenant denial; `SecurityHardeningTest` covers the login
  **timing** oracle (R1), MFA URI issuance, device labelling, and CORS.
- **Enumeration resistance** by design: uniform login failure, uniform
  forgot-password success, uniform permission denial, `404` for foreign-tenant ids.
- **No destructive deletes** of history: copy removal is a status; refresh-token reuse
  revokes families rather than silently continuing.

_This analysis reflects the code as of the rebrand to J'otheca (commit on branch
`arena/a775b9a4-j-otheca`). Maven/Flutter toolchains are unavailable in this environment,
so behavior is described from source; run `mvn verify` and `flutter test` locally to
confirm the runtime suite (151 backend tests + Flutter widget/unit tests)._
