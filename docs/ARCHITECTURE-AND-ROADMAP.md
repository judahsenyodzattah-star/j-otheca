# J’otheca — System Audit, Architecture & Phased Roadmap

**Status:** Planning baseline · v0.1 · 2026-08-10
**Repository:** `jotheca/` (greenfield — see §0)

---

## 0. Audit Finding: Current State

> **Important correction to the project brief.** The brief describes J’otheca as an *existing* project with a Spring Boot backend, Flutter app, migrations, AI services, and automated tests. **The workspace contained no code.** You confirmed we are starting from scratch.
>
> Consequence: the "do not rebuild / extend instead of replace" rule is applied *going forward* — we design clean boundaries now so that every future phase extends rather than rewrites. Nothing described in the brief is implemented yet; this document is the gap analysis: **the gap is 100% of the system**.

Everything below therefore covers two things:

1. **Target architecture** — the blueprint every phase builds toward.
2. **Gap report per capability area** — delta between the brief and what exists (nothing), turned into a phased build plan with exit criteria.

---

## 1. Product Vision (fixed reference)

> An intelligent, secure, **multi-tenant SaaS library ecosystem** serving university, school, public, private, corporate, research, personal, and author libraries — physical-book first, with a global user account connecting people to many libraries through **memberships**, and subscriptions flowing Library → J’otheca.

Non-negotiables that constrain every design decision:

| # | Invariant | Architectural consequence |
|---|-----------|---------------------------|
| 1 | User → Membership → Library (never User → Library) | `membership` is the central security object |
| 2 | Hard tenant isolation, tested continuously | Tenant filtering at identity, service, repository, DB, cache, storage, search, and vector layers |
| 3 | Never trust a client-supplied tenant ID | Tenant resolved server-side from JWT + membership table; a `Library-Id` header is *validated*, never *believed* |
| 4 | SUPER_ADMIN ≠ LIBRARY_ADMIN (strict separation) | Two authorization planes: platform plane and tenant plane |
| 5 | Physical-first circulation workflow | Loan lifecycle is modeled on librarian-mediated pickup, not instant digital checkout |
| 6 | No hard-coded plan limits | Entitlements resolved from plan configuration at runtime |
| 7 | Backend enforcement for everything (uploads, quotas, policies) | The app is a friendly client, not a trust boundary |

---

## 2. Target System Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │                CLIENTS                       │
                        │  Flutter app (Android / iOS) · Flutter Web   │
                        └───────────────┬─────────────────────────────┘
                                        │ HTTPS / JSON (REST, OpenAPI)
                        ┌───────────────▼─────────────────────────────┐
                        │           API GATEWAY / EDGE                 │
                        │  TLS · rate limiting · security headers      │
                        │  request logging (no secrets)                │
                        └───────────────┬─────────────────────────────┘
          ┌─────────────────────────────┼─────────────────────────────┐
          │                             │                             │
┌─────────▼─────────┐       ┌───────────▼───────────┐       ┌─────────▼─────────┐
│  PLATFORM PLANE    │       │    TENANT PLANE        │       │   AI / SEARCH      │
│  (SUPER_ADMIN)     │       │  (per-library ops)     │       │   SERVICES         │
│  tenants · plans · │       │  catalog · circulation │       │  embeddings ·      │
│  billing · health· │       │  members · fines ·     │       │  semantic search · │
│  audit · flags     │       │  reviews · analytics   │       │  recommendations   │
└─────────┬──────────┘       └───────────┬───────────┘       └─────────┬─────────┘
          │        shared Spring Boot modular monolith       │
┌─────────▼──────────────────────────────────────────────────▼─────────┐
│                         PERSISTENCE & INFRA                           │
│  PostgreSQL 16 (+ pgvector) · Redis (cache/ratelimit/tokens)          │
│  MinIO object storage · Flyway migrations · Mail worker · FCM push    │
└───────────────────────────────────────────────────────────────────────┘
```

### 2.1 Recommended stack (defaults — confirm in §13)

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Language | **Java 21 LTS** | Records, virtual threads, pattern matching; LTS |
| Framework | **Spring Boot 3.x**, Spring Security 6, Spring Data JPA (Hibernate 6) | Matches the brief |
| API | REST + OpenAPI (springdoc), DTOs, Bean Validation | Brief §40 |
| DB | **PostgreSQL 16** + Flyway | Migrations-first, no manual schema edits (§41) |
| Multitenancy | **Shared schema + `tenant_id` discriminator**, Hibernate filters **and** Postgres Row-Level Security | Pragmatic for hundreds–thousands of tenants; RLS as defense-in-depth |
| Cache | **Redis** | Cache, rate limits, token denylist, job locks; **every key namespaced by tenant** |
| Object storage | **MinIO** (S3 API) | Tenant-scoped key prefixes + short-lived presigned URLs; credentials never reach the client |
| Vector store | **pgvector** (recommended) | Tenant filter enforced by the same SQL engine already isolating tenants — removes the main cross-tenant-leak risk of a separate vector DB. Chroma remains a documented alternative. |
| Push | Firebase Cloud Messaging | Required for iOS/Android push later |
| Email | SMTP provider (dev: Mailpit) | Mailbox for approval/pickup/fine flows |
| Payments | **Paystack** primary (GHS, cards, mobile money; excellent for Ghana), Stripe adapter optional | Library→J’otheca subscriptions; later Patron→Library via subaccounts/transfers |
| Backend tests | JUnit 5, Testcontainers, Spring Security Test | Isolation test harness (§10) |
| Frontend | **Flutter 3 / Dart 3**, **Riverpod** (recommended), go_router, dio, freezed | Kept Flutter per brief — **no React** |

### 2.2 Monorepo layout

```
jotheca/
├── backend/                    # Spring Boot — modular monolith (package-by-feature)
│   └── src/main/java/com/jotheca/
│       ├── platform/           # SUPER_ADMIN plane: tenants, plans, billing, health
│       ├── identity/           # users, auth, sessions, JWT, refresh rotation
│       ├── tenancy/            # TenantContext, filters, guard services
│       ├── membership/         # memberships, invitations, policies, verification
│       ├── catalog/            # books, copies, branches, shelf locations, images
│       ├── circulation/        # loans, reservations, renewals, fines
│       ├── engagement/         # ratings, reviews, moderation
│       ├── publishing/         # author pages, public book pages
│       ├── billing/            # subscriptions, invoices, payments, entitlements
│       ├── notification/       # in-app, email, push orchestration
│       ├── audit/              # audit log pipeline + queries
│       ├── ai/                 # embeddings, semantic search, recommendations
│       └── common/             # DTO helpers, error model, web config
├── mobile/                     # Flutter (also builds the web app)
│   └── lib/
│       ├── core/               # theme, routing, api client, storage, utils
│       └── features/           # auth, discovery, library, catalog, loans, admin…
├── infra/
│   ├── docker-compose.yml      # postgres, redis, minio, mailpit, backend
│   └── env/                    # .env templates (no real secrets committed)
└── docs/                       # this file, API notes, decision records (ADRs)
```

> A **modular monolith** is intentional: the scaling unit for V1 is the tenant count, not microservices. Module boundaries mirror future service seams if extraction is ever justified.

---

## 3. Multi-Tenancy & Tenant Isolation (defense in depth)

Isolation is enforced at **nine layers**; a bug in any one must not break the system.

| # | Layer | Mechanism |
|---|-------|-----------|
| 1 | Authentication | JWT contains `userId`, global role, and nothing tenant-authoritative |
| 2 | Tenant resolution | Server resolves tenant from request context (`X-Library-Id` header or path) **and verifies an active membership (or SUPER_ADMIN)** before proceeding |
| 3 | Authorization | Method-level guards (@PreAuthorize) + membership-role checks in service layer |
| 4 | `TenantContext` | ThreadLocal set once per request by a resolver filter; fail-closed (no tenant = no tenant-scoped query possible) |
| 5 | Repository | Global `@Filter(name="tenantFilter")` on all tenant-owned entities; JPA Specifications append `tenant_id = :current` |
| 6 | Database | **Postgres RLS policies** keyed on `app.tenant_id` session var + FK/unique constraints including `tenant_id` |
| 7 | Cache | Redis keys always `t:{tenantId}:…` — enforced by a `TenantCacheKey` helper, never string concatenation |
| 8 | Object storage | MinIO keys `tenant/{tenantId}/…`; presigned URLs issued only after service-layer authorization |
| 9 | Vector/search | Every embedding row carries `tenant_id`; all ANN queries are filtered (`WHERE tenant_id = …`) — PostgreSQL native |

**Golden rule encoded as a test harness (§10):** *a request authenticated as Library A attempting to reach any Library B resource must fail — for every endpoint class, forever.*

---

## 4. Domain Model (target entities)

Convention: every tenant-owned row has `tenant_id`, `created_at/by`, `updated_at/by`, and soft delete (`deleted_at`) where the brief asks for it.

### 4.1 Identity & platform
- **users** — global account: email, password hash (BCrypt), name, phone, avatar ref, status; **no library FK**
- **auth_sessions** — refresh-token rotation: token family, device info, IP, expiry, revoked flag (session/device management, hijack detection)
- **verification_tokens** — email verification, password reset, institutional-email verification
- **audit_logs** — append-only: `tenant_id NULLABLE` (platform events have none), actor, action, entity, before/after metadata JSON, IP, timestamp
- **feature_flags**, **platform_settings** — SUPER_ADMIN managed

### 4.2 Tenancy & membership
- **libraries** — `uid` (`LIB-H92KD8` style, generated), name, slug, type (university/school/public/private/corporate/research/personal), country, city, address, geo, description, logo/banner refs, opening hours, services, status (PENDING_APPROVAL, ACTIVE, SUSPENDED), visibility, rating aggregates
- **memberships** — `user_id + library_id` (unique), **role** (LIBRARY_ADMIN/LIBRARIAN/PATRON), **membership_type** (STUDENT/STAFF/FACULTY/RESEARCHER/ALUMNI/PUBLIC_MEMBER/GUEST/AUTHOR), status (PENDING/APPROVED/REJECTED/SUSPENDED/EXPIRED), verification state + payload (student ID, programme, dept…), membership number, joined/expires dates — *the join entity for everything a user does in a library*
- **membership_policies** — per library: PUBLIC / APPROVAL_REQUIRED / STUDENT_ONLY / STAFF_ONLY / INVITATION_ONLY / DOMAIN_VERIFIED / CUSTOM + required-fields config
- **invitations** — links/QR/codes: slug (e.g. `pentecost`), max uses, expiry, allowed role/type, active flag
- **favorite_libraries**, **library_shares** (discovery features)

### 4.3 Catalog & inventory
- **books** — title, subtitle, author(s), ISBN (unique per tenant), publisher, pub date, language, description, category, cover ref, rating aggregates, published-page slug
- **book_copies** — the physical item: barcode/QR, accession no., condition, status (AVAILABLE/BORROWED/RESERVED/LOST/DAMAGED/MAINTENANCE/REMOVED), branch, shelf location
- **branches**, **shelf_locations** — building → floor → section → shelf → row ("Shelf A-12, Floor 2, Computer Science")
- **categories** — per-tenant tree
- **files / storage_objects** — every uploaded asset: owner entity, kind (COVER/LOGO/BANNER/AVATAR/DOC/ID), size, variants (thumb/medium/original), **tenant usage accounting**

### 4.4 Circulation
- **loans** — copy, membership, issued by (librarian), issue/ due/ return dates, renewal count, status
- **reservations** — lifecycle RESERVED → WAITING_FOR_PICKUP → READY_FOR_PICKUP → COLLECTED → (BORROWED) → RETURNED; queue position, pickup deadline, expiry, cancellation
- **circulation_policies** — per library (per membership-type overrides): borrow limit, loan period, grace period, renewal rules, max reservations, pickup deadline
- **fines** — type (LATE/LOST/DAMAGED/REPLACEMENT), amount, currency, status, calculated from **fine_policies**; imposed by the library, never the platform

### 4.5 Engagement
- **ratings** — 1–5, **unique (membership, book)** → one rating per person per library per book (update, not duplicate)
- **reviews** — text, verified-borrower flag, moderation state, helpful votes, reports; anonymous **only if** policy allows

### 4.6 SaaS / billing (platform plane)
- **subscription_plans** — STARTER / PROFESSIONAL / ENTERPRISE + **plan_limits JSON** (storage GB, members, books, staff, AI calls…). *Code reads limits from here — zero hard-coded numbers.*
- **library_subscriptions** — current plan, period, status, renewal
- **invoices / payments** — Paystack references, platform-side (Library→J’otheca)
- **storage_usage / quota_events** — current usage, warnings (80%/100%), add-on purchases
- **patron_payments** — later: Library→Patron charges (membership fees, fines, book sales) under library keys

### 4.7 AI & notifications
- **book_embeddings** — `book_id`, `tenant_id`, vector(768/1536), model version
- **ai_usage** — per-tenant counters (plan-limited)
- **notifications** — channel (IN_APP/EMAIL/PUSH), type (20+ events in §32 of the brief), payload, read state; **device_tokens** for FCM

---

## 5. Roles & Permissions Matrix

| Capability | SUPER_ADMIN | LIBRARY_ADMIN | LIBRARIAN | PATRON |
|---|:-:|:-:|:-:|:-:|
| Manage/approve/suspend libraries, plans, platform billing, flags | ✅ | — | — | — |
| Borrow / issue / return / fine patrons | **❌** | — | ✅ (issue/return) | own |
| Manage library settings, branding, policies, staff | ❌ | ✅ | — | — |
| Manage books/copies/inventory | ❌ | ✅ | ✅ | — |
| Manage memberships (approve/reject) | ❌ | ✅ | ✅ (assist) | own join requests |
| Search, reserve, rate, review, own history/fines | ❌ | ✅ | ✅ | ✅ |
| Library analytics, reports, storage usage | overview | ✅ | partial | — |
| Platform analytics, revenue, health, security events, audit | ✅ | — | — | — |

Notes per brief: librarians are **appointed**, never self-registered; membership type ≠ role; SUPER_ADMIN is deliberately excluded from day-to-day circulation.

---

## 6. Subscription Plans & Entitlements (config-driven)

```jsonc
// subscription_plans.limits — the ONLY place limits live
{ "storage_gb": 10, "max_members": 500, "max_books": 2000, "max_staff": 2,
  "ai_calls_monthly": 1000, "branches": 1, "reservations": true,
  "notifications": ["in_app"], "analytics": "basic", "barcode_qr": false,
  "api_access": false, "sso": false }
```

| Dimension | STARTER | PROFESSIONAL | ENTERPRISE |
|---|---|---|---|
| Target | authors, personal, small orgs | schools, medium institutions | universities, gov, large orgs |
| Storage / members / books / staff / AI | low caps | raised caps | business-rule caps ("unlimited*") |
| Branches | 1 | up to N | multiple |
| Reservations · notifications · analytics | —/basic | ✅ | ✅ advanced |
| Barcode/QR · API · SSO-ready · priority support | — | QR ✅ | ✅ all |
| Storage add-ons | ✅ purchasable | ✅ | ✅ |

**EntitlementService** answers `can(tenant, capability)` / `limit(tenant, key)`; every write path that consumes a limited resource checks it. Quota breaches fail server-side with a clean upgrade prompt.

---

## 7. API Design Conventions + Surface Map

Conventions: `/api/v1/…` · DTOs everywhere · Bean Validation · uniform error envelope `{code, message, details, traceId}` · correct HTTP semantics · pagination (`page/size/sort` or cursor for feeds) · OpenAPI at `/swagger-ui` · tenant selected via validated `X-Library-Id` header · idempotency keys on payment/mutation endpoints where retries are plausible.

| Domain | Representative endpoints |
|---|---|
| Auth | `POST /auth/register · /auth/login · /auth/refresh · /auth/logout · /auth/verify-email · /auth/forgot|reset-password` (rate-limited) |
| Me | `GET /me · PATCH /me · GET /me/memberships · GET /me/notifications` |
| Discovery | `GET /libraries?search=&city=&country=&type=&near= · GET /libraries/{slug} · POST /favorites` |
| Joining | `GET /join/{inviteSlug} · POST /libraries/{slug}/join · POST /libraries/{slug}/join/verify` |
| Onboarding | `POST /onboarding/library-requests → /plans → /payment/callback → admin approval` |
| Tenant (library) plane | `…/books · /copies · /categories · /members · /loans · /reservations · /fines · /policies · /invitations · /reports · /settings · /branding` |
| Membership card | `GET /memberships/{id}/card` (QR payload signed) |
| Platform plane | `/platform/libraries · /platform/plans · /platform/billing · /platform/analytics · /platform/audit · /platform/flags` |
| AI | `POST /search/semantic` · `GET /recommendations` (tenant-scoped) |
| Files | `POST /files/presign` → direct-to-MinIO upload → `POST /files/complete` (content sniffed, never extension-trusted) |

---

## 8. Flutter Frontend Architecture

Feature-first, Riverpod state, `go_router` for deep links (`jotheca.app/join/{slug}`, `/book/{slug}` — invitation & publishing URLs work on web cleanly), `dio` with auth/tenant interceptors, `freezed` models generated from the OpenAPI spec to kill drift.

Screen groups by journey: **auth → empty-state ("You haven't joined a library yet") → discovery → join/invitation → home (per active library) → catalog/search → book detail → loans/reservations/fines → membership card (QR) → profile**; staff mode adds **desk (issue/return via scan), members, inventory**; admin adds **dashboard, policies, branding, staff**; a separate **platform console** for SUPER_ADMIN.

UX system per brief §38–39: skeleton loaders, real empty/error states, toasts + confirmations, restrained motion, **light/dark**, WCAG AA (contrast, focus, semantic labels, reduced-motion, ≥48dp touch targets), responsive breakpoints for the web app.

---

## 9. Infrastructure

`infra/docker-compose.yml` (dev & CI): `postgres:16` (+pgvector), `redis:7`, `minio`, `mailpit`, `backend`. Production notes: managed Postgres w/ RLS, secrets via environment/KMS (never in repo, never logged), structured JSON logs with `traceId`, daily backups + restore drills, S3-compatible bucket policies per environment.

---

## 10. Testing Strategy

| Suite | Scope | Key fixture |
|---|---|---|
| Unit | services, policies, entitlement math | pure |
| Repository | tenant filter + RLS actually applied | Testcontainers Postgres |
| Controller | authZ matrix (§5) per endpoint | @WebMvcTest + SecurityTest |
| **TenantIsolationTest** | **A-caller vs B-resource across EVERY controller** — auto-derived from a registry of tenant-scoped endpoints | runs in CI, blocks merge |
| Integration | full flows: onboarding→approval, reserve→pickup→loan→return→fine, invite join, upload→quota | Testcontainers (pg+redis+minio) |
| Security | brute-force lockout, token rotation reuse detection, upload content sniffing, IDOR probes | dedicated suite |
| Flutter | golden widgets for core screens, Riverpod unit tests, deep-link tests | CI |

Also in CI: backend build+tests, Flyway migration validation, OpenAPI diff, Flutter analyze+test, dependency scanning, coverage floor.

---

## 11. Phased Roadmap

Your brief's phase list was truncated mid-sentence — **Phases 1–4 were missing**, so they're inferred here and mapped onto your Phases 5–11. Each phase ends with: tests green (incl. isolation suite) + docs updated + demo-able on the live preview.

| Phase | Scope | Exit criteria (abridged) |
|---|---|---|
| **0 — Bootstrap** | monorepo skeleton, backend app boots w/ health endpoint, Flutter app skeleton w/ theme/routing, docker-compose, CI, ADR-001..004 | `docker compose up` → backend healthy; app runs |
| **1 — Identity & security core** | users, JWT login/refresh rotation, email verify, password reset, rate limiting, security headers, audit trail, SUPER_ADMIN seed | auth flows e2e; audit rows written; login brute-force protection proven |
| **2 — Tenancy & membership** | libraries + UID (`LIB-XXXXXX`), TenantContext + Hibernate filter + **RLS**, memberships, policies, invitations/links/codes/QR, join flows, `TenantIsolationTest` harness | A↛B isolation suite passes for all endpoints so far |
| **3 — Onboarding & subscriptions** | library request → plan select → **Paystack** (sandbox) → verification → SUPER_ADMIN approval → activation; plans + `EntitlementService`; storage-usage ledger | full onboarding works against sandbox payments; limits enforced server-side |
| **4 — Catalog & discovery** ✅ | categories tree, books (tenant-unique normalized ISBN + slug, published flag), copies (auto barcodes `{branch}-{seq}`, accession no., spec status enum incl. REMOVED-soft-delete), branches (plan `maxBranches` enforced), `Branch→Building→Floor→Section→Shelf→Row` shelf locations w/ display string, member search (title/subtitle/author/subjects/ISBN LIKE + ISBN-exact + category subtree + language; patrons published-only), rating aggregate columns, catalog RLS (V6); *done this phase* — discovery search across libraries & favorites/nearby pages ride on the existing library discovery API | ✅ 45/45 tests incl. 10 catalog-flow + 4 catalog-isolation cases; live demo verified via curl (patron search, staff CRUD, cross-tenant 404/403) |
| **5 — Inventory, borrowing, reservations** ✅ | loan policies per tenant, desk issue/return via barcode + card-number scan (`GET /circulation/members/lookup` backs the membership-QR card scan), renewals (own/staff, cap, reservation-blocked), reservation lifecycle **RESERVED→WAITING_FOR_PICKUP→READY_FOR_PICKUP→COLLECTED→(loan BORROWED)→RETURNED** with copy promotion on return + lazy pickup-deadline expiry (REQUIRES_NEW sweep), fines (LATE auto on return; LOST/DAMAGED/REPLACEMENT/MANUAL staff-assessed closing loans + retiring copies; settle/waive), notifications v1 (in-app + email mirror via MailService), plan feature-gate (`reservations:false` on STARTER), circulation RLS (V8) | ✅ 55/55 tests incl. 8 circulation-flow + 2 circulation-isolation cases; live curl demo: issue→return→auto fine→desk payment→notification feed |
| **6 — Author & personal libraries + publishing** ✅ | PERSONAL type was already in the enum/constraint — wired end-to-end via onboarding; `GET /api/v1/public/libraries/{slug}/books[/{bookSlug}]` public pages (published-only, ACTIVE+visible libraries only), share URLs from `app.public-base-url`, Flutter `/book/:library/:bookSlug` deep link; `book_interests` ("I want this", idempotent, staff demand view, author notification when `author_user_id` set); physical sales: `sale_price_minor` + `purchase_requests` (PENDING→FULFILLED @ desk w/ scanned copy → REMOVED) / CANCELLED; RLS (V10) | ✅ 62/62 tests incl. 6 publishing-flow cases; live curl: public page, hidden-book 404, interest signal to author, sale handover drops public availability |
| **7 — Media, ratings, reviews** ✅ | `StorageService` port (in-memory dev / **MinIO** prod via `app.storage.provider`), magic-byte **content sniffing** (client content-type never trusted), re-encode + FULL(≤1600px)/THUMB(≤320px) variants via ImageIO, tenant-prefixed keys `t/{tenantId}/…`, **storage quota hard-checked** from `plan.limits.storageGb` before any byte is stored (plus 8 MB/file cap → 413); attaches: book **covers**, library **logo/banner**, user **avatars** (avatars NEVER public — public plane serves covers/logos/banners of ACTIVE+visible libraries only); `media_objects` metadata + status-flag deletion, RLS (V12). **Ratings** 1–5, unique per (book,membership), upsert-in-place, `books.ratingAvg/ratingCount` re-denormalized on every write. **Reviews**: one per member/book (soft-delete + reactivation keeps the unique key), lifecycle PENDING→APPROVED/REJECTED w/ moderation note + queue, edits re-enter moderation, **anonymous display gated by per-library policy** (`libraries.allow_anonymous_reviews`, admin toggle, real identity staff-visible), **reports** (one/member, resolved by approve/reject, dismissible), **helpful votes** (toggle, no self-votes); public approved-review feed on the public book page | ✅ 77/77 tests incl. 12 media/review-flow + 3 media/review-isolation cases; live curl: rating upsert aggregates, approve→public anonymized feed, policy-off 403, fake-PNG rejected, cover bytes public/thumb, cross-tenant media 404, avatar never public |
| **8 — Advanced security & audit** ✅ | **MFA (real TOTP, RFC 6238)**: enroll (one-time `otpauth://` secret) → enable-with-code → login issues a 5-min `typ=mfa` challenge (unusable as access token — filter accepts only `typ=access`) → `/auth/mfa/verify`; disable needs password+code; secret never audited/logged. **Lockout**: 10 failures → 15-min 423 lock with `retryAfterSeconds`, durable counters in users (V13), REQUIRES_NEW `LoginGuard` so the throwing tx can't roll the counter back; no existence oracle for unknown emails. **Sessions/devices**: `sid` claim marks "current", device labels + IP + last-used, per-device revoke + revoke-all, cross-account revoke → 404. **Rate limiter port**: memory default / **Redis** distributed (INCR+PEXPIRE, fail-open by design with loud logging) behind `app.rate-limit.backend`; `/auth/mfa/verify` added to throttled paths. **Headers review**: CSP `default-src 'none'` on `/api/**` + Permissions-Policy everywhere (Swagger untouched). **Audit viewers**: tenant (`/tenant/audit`, LIBRARY_ADMIN, hard-pinned tenant) + platform (`/platform/audit`, SUPER_ADMIN, cross-tenant filters), audited actions extended (`ACCOUNT_LOCKED`, `MFA_*`, `SESSIONS_*`); **pentest checklist** `docs/SECURITY-PENTEST-CHECKLIST.md` maps every control to code + test | ✅ 86/86 tests incl. 7 security-hardening cases (lockout, sessions, TOTP E2E, viewers, headers) + 2 new isolation cases; live curl: 423-after-10, device revoke→TOKEN_REUSE_DETECTED, MFA challenge→401-as-access→verify, viewers scoped, headers present |
| **9 — Quotas, billing, analytics** ✅ | **storage_usage append-only ledger** (V14, +upload/−retire/−delete, pre-P9 backfilled, RLS V15) now backs the media quota hard-check; **storage add-ons**: checkout `gb × GH₵10` (config `app.billing.storage-addon-price-per-gb-minor`), stacked onto the plan cap at read time (`withStorageAddon`); **lifecycle**: renewal checkout extends +30d on provider-verified confirm (`SubscriptionLifecycleService.renewFromPayment`, REQUIRES_NEW), lazy EXPIRED sweep on first overdue read (+audit+admin email), renewal re-activates; **platform plan change** `POST /platform/libraries/{id}/plan` with downgrade guard — 409 `DOWNGRADE_BLOCKED` carrying per-dimension `{current,limit}` when members/staff/books/branches/storage exceed the target plan, audited `SUBSCRIPTION_CHANGED {from,to,direction}`, plan codes demoted from CHECK-constraint to data; **patron rail**: `fines/{id}/pay-online` + `confirm-online {reference}` — server-side provider verify, payment must match fine+tenant+amount else `PAYMENT_MISMATCH`, non-owner/cross-tenant 404, settlement traced `online (provider ref)` + receipt notification; **three dashboards**: `tenant/analytics/dashboard` (admin: quota bars incl. add-on-aware storage cap, loans active/overdue, reservations, open fines, moderation backlog, tenant payments total, top-demanded/top-rated), `tenant/analytics/me` (member's own numbers), `platform/analytics/dashboard` (SUPER_ADMIN: library/membership breakdowns, MRR by plan from ACTIVE subs × plan prices, PAID totals by purpose, storage total, 24h audit volume) | ✅ 100/100 tests incl. 11 billing/analytics-flow cases (renew+30d, add-on stacking, lazy expire flip, blocked/allowed plan changes, online fine settle, ledger netting) + 3 isolation cases (dashboards per-tenant, cross-tenant rails 404, platform dash role-only); live curl demo 26 checks: renewal extension, add-on 50+3GB, downgrade round-trip PROFESSIONAL↔STARTER, fine pay-end-to-end, MRR 64600 minor across plans |
| **10 — AI** ✅ | pgvector embeddings (V16 `book_embeddings` tenant-scoped + V17 PG-only RLS + `vector` ext + expressional HNSW index; deterministic `local-hash-256` dev embedder, pluggable OpenAI `text-embedding-3-small` via env), **tenant-filtered** semantic search ("beginner Java books after 2023" → strict `publishedAfter` filter, 0.7·cosine+0.3·lexical scoring, `matchedTokens`/`why` explanations, staff `includeUnpublished`), recommendations (weighted centroid of loans×1 + ≥4★×2 + interests×1.5, consumed never echoed, cold-start top-rated, `because[]` reasons), **guarded librarian assistant** (staff-only; DEMAND/OVERDUE/TOP_RATED/SEARCH intents, grounded answers + citations, audit logs intent + question *length* only — never the text), demand insights (want-it + reservation queues + zero-hit searches as uncatalogued demand), plan-metered quota (`aiCallsMonthly`, overage → 403 `PLAN_LIMIT_EXCEEDED` + audit, flipped by plan JSON — data not code), `POST /ai/embeddings/rebuild` (LIBRARY_ADMIN, tenant-pinned full replace); catalog service hooks (search telemetry REQUIRES_NEW, embedding refresh/remove on writes); Flutter `/ai` page | ✅ 112/112 tests incl. 10 AI-flow cases (Ashesi sandbox: deterministic ranking, strict year filter excl. yearless rows, in-domain recs, assistant grounding + citations + audit-without-question-text + patron 403, zero-hit demand capture, tenant-pinned rebuild, plan-quota 403→restore-as-data) + TenantIsolationTest 33 cases incl. 2 new (foreign embeddings never scored — Accra hits / Pentecost excludes — recs stay in-caller's-tenant); live curl demo 14/14: cold-start 8 candidates, TFA top w/ explanations, "history after 2010"→Sapiens 2015, cross-tenant empty, usage 6/10000 byFeature, rebuild ≥3 embeddings |
| **11 — Hardening** ✅ | **accessibility**: WCAG 2.1 AA audit (`docs/ACCESSIBILITY-AUDIT.md`) — QR `semanticsLabel`, tooltips on every icon-only control, semantic headers, `TextInputAction.search`, contrast via Material 3 roles. **perf**: V18 b-tree sweep (books tenant+published+title, libraries status+name, zero-hit search events, cross-tenant audit, notification unread poll) + V19 PG-only pg_trgm GIN for `%like%` member search; N+1 audit (hot paths already batched — no fix needed); **edge caching**: anonymous plane `max-age=60 public` (media 30d, previews `no-store`), private endpoints never public-cacheable. **OpenAPI**: shared `ApiError` envelope schema registered + standard error responses on every operation via post-generation customizer (add-if-absent; 423 login/mfa only, 429 auth only, 403 tenant/platform, 404 on path-param routes, 409 on tenant/platform writes), `@Operation` summaries on the AI surface. **ops**: `scripts/backup.sh`/`restore.sh` + quarterly drill doc (RPO ≤15min WAL, RTO ≤1h), runbooks RB-01…RB-08, release checklist gate, `scripts/load-test.sh` + `docs/PERF-BASELINE.md` | ✅ 120/120 tests incl. 8 new hardening cases (cache rules, envelope + per-endpoint error docs, operation summaries, V18 indexes present); live `demo-p11.sh` 6/6 + load baseline on hot endpoints (catalog search p95 29.7ms vs 150ms budget, AI search p95 49.8ms vs 400ms); app rebuilt, all 19 migrations applied on boot, `demo-p10.sh` regression 14/14 |

Change-control rule for everything above: **extend, don't replace** — new capability = new module + migration; refactors need an ADR + passing regression tests first.

---

## 12. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Tenant leak is existential | 9-layer model + CI-blocking isolation suite + RLS |
| Payments in Ghana | Paystack first (GHS, MoMo, cards); abstraction lets us add Stripe |
| Scope (43 sections, 11 phases) | Strictly one phase at a time; this doc is the contract |
| Flutter web SEO for public pages | Server-rendered landing/metadata for `/join/*` and `/book/*` if needed in P6 |
| Vector cross-tenant leaks | pgvector + mandatory tenant predicate; red-team test in P10 |

---

## 13. Decisions I've Made by Default (push back on any)

1. **Modular monolith**, not microservices (extract later if a module earns it).
2. **Shared schema + RLS**, not schema-per-tenant (weighed against thousands of small/author tenants).
3. **pgvector over Chroma** for V1 (isolation enforced in the same engine; Chroma adapter behind an interface — nothing lost).
4. **Paystack** as primary payment provider (Ghana fit); Stripe adapter stubbed.
5. **Riverpod** for Flutter state management.
6. Library UID format: `LIB-` + 6 chars of Crockford base32, unique, permanent.
7. Inferred Phases 1–4 (your message cut off before listing them) — see §11.

## 14. Next Step

On your go-ahead I execute **Phase 0 → Phase 1 → Phase 2** in order (bootstrap, then identity, then tenancy+membership with the isolation test suite), showing each milestone as a live preview. Estimated 3 substantive work sessions to land Phase 2.
