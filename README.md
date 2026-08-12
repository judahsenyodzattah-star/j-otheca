# LibraNet

Intelligent, secure, **multi-tenant SaaS library ecosystem** — physical-book first, with global user accounts joining many libraries through **memberships**.

- **Backend:** Java 21 · Spring Boot 3.5 · Spring Security · Spring Data JPA/Hibernate 6 · PostgreSQL 16 (RLS) · Flyway · JWT
- **Mobile + Web app:** Flutter 3 / Dart 3 (`mobile/`)
- **Infra:** Docker Compose (`infra/`) — Postgres+pgvector, Redis, MinIO, Mailpit
- **Authoritative plan:** [`docs/ARCHITECTURE-AND-ROADMAP.md`](docs/ARCHITECTURE-AND-ROADMAP.md) — read before changing anything. ADRs live in `docs/adr/`.

## Non-negotiables (the contract)

1. **User → Membership → Library.** A global account never has a direct library FK. Every library action happens through a membership.
2. **Tenant isolation at 9 layers** (auth → resolution → guards → `TenantContext` → Hibernate filter → Postgres RLS → cache keys → storage keys → vector search). Never trust a client-supplied tenant ID: `X-Library-Id` is *validated against the memberships table*, not believed.
3. **SUPER_ADMIN runs the platform, not libraries.** Platform plane (`/api/v1/platform/**`) is disjoint from the tenant plane (`/api/v1/tenant/**`).
4. **Backend enforces everything** — quotas, uploads, policies. Clients are friendly, never trusted.
5. **Extend, don't rewrite.** New capability = new module + Flyway migration. Refactors need an ADR + green `TenantIsolationTest`.
6. Every tenant-scoped endpoint is covered by the isolation suite — CI blocks on it.

## Quick start (real Postgres, full stack)

```bash
cd infra && cp env/*.example ../backend/.env   # adjust secrets
docker compose up -d postgres redis minio mailpit
cd ../backend && ./mvnw spring-boot:run        # API on :8080
cd ../mobile && flutter run                    # app
```

## Quick start (dev profile, zero dependencies — used in the sandbox demo)

```bash
cd backend && mvn -q -Dspring-boot.run.profiles=dev spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html · Health: /actuator/health
```

The `dev` profile uses H2 (Postgres mode) so the app runs with no Docker. **Note:** the Postgres RLS defense layer self-activates only on real Postgres (see `V2__RlsPolicies`); the remaining isolation layers are active everywhere and are what the isolation suite tests.

### Seeded demo accounts (dev profile only)

| Account | Password | Purpose |
|---|---|---|
| `admin@libranet.app` | `Admin12345!` | SUPER_ADMIN (platform console) |
| `libadmin@libranet.app` | `Library12345!` | LIBRARY_ADMIN of *Pentecost University Library* |
| `staff@libranet.app` | `Library12345!` | LIBRARIAN of *Pentecost University Library* |
| `patron@libranet.app` | `Patron12345!` | member of *Accra Central Public Library* |
| `accraadmin@libranet.app` | `Library12345!` | LIBRARY_ADMIN of *Accra Central Public Library* |
| `author@libranet.app` | `Author12345!` | owner of *Auntie Esi Writes* (PERSONAL author library) |

Seeded libraries: `pentecost` (APPROVAL_REQUIRED) · `accra-central` (PUBLIC) · `ashesi` (INVITATION_ONLY, code `ASH-2026`).

## Layout

```
backend/   Spring Boot modular monolith (package-by-feature inside com.libranet)
mobile/    Flutter app (Android/iOS) that also builds the web app
infra/     docker-compose + env templates
docs/      architecture roadmap + ADRs
```

## Testing

```bash
cd backend && mvn -q verify    # unit + isolation + security suites (H2; no Docker needed)
# TenantIsolationTest is the merge blocker: caller in Library A must never reach Library B.
```

## Status

| Phase | State |
|---|---|
| 0 Bootstrap | ✅ monorepo, CI, Docker, ADRs |
| 1 Identity & security core | ✅ JWT + refresh rotation, verification, rate limiting, audit |
| 2 Tenancy & membership | ✅ TenantContext + Hibernate filter + RLS migration, libraries/UIDs, join/invitations |
| 3 Onboarding & subscriptions | ✅ plan catalog (data-driven limits), request → payment → approval → provisioning, entitlements |
| 4 Catalog & discovery | ✅ categories tree, books (ISBN unique per tenant), copies w/ auto barcodes, branches/shelf locations, tenant-filtered search, seeded demo catalogs |
| 5 Circulation | ✅ policies, desk issue/return via barcode+card scan, renewals, full reservation lifecycle w/ queue promotion & pickup expiry, fines (late auto / lost / damaged / waived), notification feed v1 (in-app + email mirror) |
| 6 Publishing & public pages | ✅ PERSONAL author libraries, public book pages `/book/{library}/{slug}` (no auth), share URLs, interest tracking w/ author signals, physical purchase requests + staff handover |
| 7 Media, ratings & reviews | ✅ content-sniffed image uploads (MinIO/in-memory storage port) w/ compression + variants, covers/logos/banners/avatars, plan-storage hard quota, 1–5 ratings w/ live aggregates, reviews w/ moderation queue, reports, helpful votes, policy-gated anonymity |
| 8 Advanced security & audit | ✅ durable login lockout (423), sessions/devices view + revoke (+all), TOTP MFA end-to-end (challenge token can't act as access token), Redis-backed distributed rate-limit option, CSP/Permissions-Policy header review, tenant + platform audit viewers with hard scoping, pentest checklist (`docs/SECURITY-PENTEST-CHECKLIST.md`) |
| 9 Quotas, billing & analytics | ✅ append-only `storage_usage` ledger (media uploads/retires/deletes) backing plan-storage quota + add-ons, subscription lifecycle (renew +30d on payment, lazy EXPIRED sweep on first overdue read with admin email), storage add-on checkouts (GH₵10/GB, stacked on plan cap), platform plan change with **downgrade blocked** (409) when usage exceeds target limits, patron fine online rail (pay-online → provider-verified confirm; cross-tenant/wrong-owner → 404), three dashboards (tenant admin quota/usage + circulation health + demand charts, member my-library, SUPER_ADMIN MRR/byPlan/payments/storage/audit-volume) |
| 10 AI | ✅ pgvector embeddings (deterministic local-hash dev embedder, pluggable OpenAI), tenant-filtered semantic search w/ NL year filters + per-hit explanations, per-member recommendations, guarded librarian assistant (demand/overdue/top-rated/catalog intents, grounded + cited), demand insights (want-it + reservation queues + zero-hit searches), plan-metered quota (`aiCallsMonthly` → 403 as data), tenant embedding rebuild; Flutter `/ai` page |
| 11 Hardening | ✅ WCAG 2.1 AA audit + Flutter semantics fixes (`docs/ACCESSIBILITY-AUDIT.md`), V18 b-tree index sweep + V19 pg_trgm GIN (PG-only), edge caching on the anonymous plane (media 30d / public pages 60s / previews no-store; private endpoints never public), OpenAPI polish (shared `ApiError` envelope documented on every operation, per-endpoint 400/401/403/404/409/423/429 add-if-absent, `@Operation` summaries on the AI surface), backup/restore scripts + quarterly drill (`scripts/backup.sh`, `scripts/restore.sh`, `docs/ops/BACKUP-AND-RESTORE.md`), incident runbooks RB-01…RB-08 (`docs/ops/RUNBOOKS.md`), load baseline + budgets (`scripts/load-test.sh`, `docs/PERF-BASELINE.md`), release gate (`docs/RELEASE-CHECKLIST.md`) |

## Deploy & ops quick links

- **Run locally (from scratch)**: `docs/RUN-LOCALLY.md` — prerequisites, backend/Flutter/staging start, seeded accounts, troubleshooting
- **Tools - **Run locally (from scratch)**: `docs/RUN-LOCALLY.md` — prerequisites, backend/Flutter/staging start, seeded accounts, troubleshooting commands**: `docs/TOOLS-AND-COMMANDS.md` — every tool in the stack with the exact commands this repo uses

- **Academic pack (FYP)**: `docs/academic/` — proposal, literature review, interim report, full dissertation (8 chapters, APA 7), defense slides/demo-script/viva bank, poster; `.docx` conversion via `docs/academic/bin/to_docx.py`
- **Staging deploy**: `docs/ops/STAGING-DEPLOY.md` (+ `infra/.env.staging.example`; `docker compose --profile full up` runs the API on real Postgres/pgvector + Redis + MinIO with profile `staging`; CI job `staging-migration-smoke` proves RLS/HNSW/trigram on real PG every push)
- Runbooks `docs/ops/RUNBOOKS.md` · Backup/restore `docs/ops/BACKUP-AND-RESTORE.md` · Release gate `docs/RELEASE-CHECKLIST.md` · Last verification `docs/RELEASE-VERIFICATION-2026-08-11.md`
- Perf baseline `docs/PERF-BASELINE.md` · Accessibility `docs/ACCESSIBILITY-AUDIT.md` · Pentest map `docs/SECURITY-PENTEST-CHECKLIST.md`

## Phase 10 API quick map

- Semantic search (members): `GET /api/v1/tenant/ai/search?q=…[&limit,&includeUnpublished staff-only]` — query understanding turns "after 2023" / "older than 1990" / "in 2019" into **strict** published-year filters (yearless rows excluded on year-filtered queries); scoring = 0.7·cosine + 0.3·lexical-coverage; each hit carries `matchedTokens[]` + `why[]` explanations; patrons see published titles only. Embeddings: deterministic `local-hash-256` dev model (SHA-256 text-hash staleness, self-healing refresh on catalog writes), pluggable `text-embedding-3-small` via `AI_EMBEDDER=openai` + `AI_OPENAI_API_KEY`; pgvector ANN path under `AI_SEARCH_BACKEND=pgvector` — **every ranking path is tenant-pinned** (native query carries `tenant_id`; isolation layer 9).
- Recommendations: `GET …/ai/recommendations` — weighted centroid of the caller's own loans (×1) + ≥4★ ratings (×2) + want-it marks (×1.5); consumed titles are never echoed back; cold start → library top-rated; every item explains itself via `because[]`.
- Guarded assistant (LIBRARIAN/LIBRARY_ADMIN only — patrons 403): `POST …/ai/assistant/ask {question}` — intents DEMAND / OVERDUE / TOP_RATED / SEARCH, answers grounded in tenant data with catalog `citations[]`; audit stores the intent + question **length** only, never the question text.
- Demand insights (staff): `GET …/ai/demand-insights` — `topDemanded[]` (want-it marks + waiting reservations per title + acquisition suggestion), `uncataloguedDemand[]` (zero-hit catalog searches, ≥2 occurrences), natural-language `summary[]`.
- Metering & ops: `GET …/ai/usage` (staff: `{used, limit, month, byFeature}`) — every AI feature counted against plan `limits.aiCallsMonthly`; overage answers **403 `PLAN_LIMIT_EXCEEDED`** + `AI_QUOTA_EXCEEDED` audit (plan JSON flips it, no code change). `POST …/ai/embeddings/rebuild` (LIBRARY_ADMIN) — full tenant re-embed, audited, proves no row leaks across tenants.
- Flutter: `/ai` page — semantic search bar with explanation chips, "For you" recommendations, staff-only assistant + demand insights and the monthly usage chip; entry from home (`AI discovery`).

## Phase 9 API quick map

- Tenant billing (LIBRARY_ADMIN): `GET /api/v1/tenant/billing/subscription` (plan + status + `effectiveLimits` = plan ∪ storage add-ons) · `POST …/billing/renew` (checkout; confirm extends exactly +30 days) · `POST …/billing/storage-addon {gb}` (GH₵10/GB via `app.billing.storage-addon-price-per-gb-minor`) · `GET …/billing/payments` (tenant-pinned history)
- Plan management (SUPER_ADMIN): `POST /api/v1/platform/libraries/{id}/plan {planCode, reason?}` — allowed when usage fits; otherwise `409 DOWNGRADE_BLOCKED` with a per-dimension `{current,limit}` breakdown. Plan codes are data (`chk_plan_code` dropped in V14); audited `SUBSCRIPTION_CHANGED {from,to,direction}`.
- Patron online rail: `POST /tenant/circulation/fines/{id}/pay-online` → checkout; `POST …/confirm-online {reference}` — payment is verified server-side, must belong to *this* fine/tenant/amount, then settles with `online (provider ref)` trace + receipt notification. Non-owners and cross-tenant callers get **404**.
- Dashboards: `GET /tenant/analytics/dashboard` (LIBRARY_ADMIN: quota bars members/books/branches/storageBytes incl. add-ons, loans active/overdue, reservations, open fines, moderation backlog, payments total, top-demanded + top-rated titles) · `GET /tenant/analytics/me` (member's own loans/fines/reservations/interests/reviews/notifications) · `GET /platform/analytics/dashboard` (SUPER_ADMIN: library/membership breakdowns, **MRR GH₵ by plan from ACTIVE subs × plan prices**, PAID payment totals by purpose, total storage, audit events/24h)
- Lifecycle: expiry is a lazy sweep inside `EntitlementService.limitsFor` (REQUIRES_NEW — reads never fail) that flips overdue ACTIVE subs to EXPIRED + audit + admin email; renewal re-activates. Storage usage is an **append-only ledger** (`storage_usage`): media upload +bytes / retire −bytes / delete −bytes; pre-P9 objects backfilled by V14; RLS backstop for `storage_usage`, `library_subscriptions`, `payments` in V15 (Postgres only).

## Phase 8 API quick map

- `POST /auth/login` → tokens OR `{mfaRequired:true, mfaToken}` when TOTP is on · `POST /auth/mfa/verify` {mfaToken, 6-digit code} → tokens (challenge tokens are `typ=mfa`, never usable as access tokens)
- Lockout: 10 failed logins/MFA codes → 423 `ACCOUNT_LOCKED` + `retryAfterSeconds` (15 min; counters durable in `users.failed_attempts/locked_until`)
- `GET /me/security/sessions` (device label, IP, last-used, `current` marker) · `DELETE /me/security/sessions/{id}` (others' ids → 404) · `POST …/sessions/revoke-all`
- `GET /me/security/mfa` · `POST …/mfa/enroll` (one-time `otpauth://` secret) · `…/mfa/enable` {code} · `…/mfa/disable` {password + code}
- Audit viewers: `GET /tenant/audit?action=&actorEmail=&from=&to=` (LIBRARY_ADMIN, pinned to own tenant) · `GET /platform/audit[?tenantId=…]` (SUPER_ADMIN, cross-tenant)
- Rate limiting behind a port: `app.rate-limit.backend=memory|redis` (+`SPRING_DATA_REDIS_*`); Redis backend is fail-open by design — lockout + BCrypt remain the hard stops

## Phase 3 API quick map

- `GET /api/v1/plans` — public plan catalog (limits are data, editable by migration)
- `POST /api/v1/onboarding/requests` — start a library (auto-checkout; dev gateway is mock)
- `GET /api/v1/onboarding/requests/mine` — track approval
- `POST /api/v1/billing/webhooks/paystack` — signature-verified provider webhook
- `POST /api/v1/billing/dev/mark-paid` — dev-only checkout simulation (dev profile)
- `GET/POST /api/v1/platform/onboarding*` — SUPER_ADMIN review queue, approve/reject
- `POST /api/v1/tenant/members/{id}/role` — LIBRARY_ADMIN appoints staff (plan-limited; **never self-serve**)

Approval requires a confirmed payment; approving creates the library (permanent `LIB-XXXXXX` UID),
activates it, makes the applicant `LIBRARY_ADMIN`, and attaches the subscription that
`EntitlementService` enforces from minute one.

## Phase 4 API quick map (tenant plane; `X-Library-Id` required, validated server-side)

- `GET /api/v1/tenant/catalog/books?q=&categoryId=&author=&publisher=&isbn=&language=&sort=` — member search (patrons see published only; staff add `includeUnpublished=true`)
- `POST/PUT/DELETE /api/v1/tenant/catalog/books[/...]` — staff title CRUD (ISBN normalized, unique per tenant; delete refused while copies exist)
- `GET/POST .../books/{id}/copies` · `PUT/PATCH/DELETE .../copies/{id}[/status]` — copies w/ auto barcodes from branch code; `BORROWED`/`RESERVED` reserved for circulation (Phase 5)
- `GET/POST/PUT/DELETE .../catalog/categories|branches|shelf-locations` — category tree, branches (plan-capped via `maxBranches`), `Branch→Building→Floor→Section→Shelf→Row` locations (e.g. "Shelf A-12, Floor 2, African Literature")
- Books carry `ratingAvg`/`ratingCount` aggregates (written by Phase 7) and a tenant-unique `slug` for public pages (Phase 6)

Isolation: every catalog row is tenant-owned and passes the same 9-layer model (cross-tenant ids answer **404**; FK spoofing across tenants rejected). RLS backstop extended to catalog tables by `V6__RlsPoliciesCatalog` on real Postgres.

## Phase 5 API quick map (tenant plane; `X-Library-Id` required)

- `GET/PUT /api/v1/tenant/circulation/policy` — loan days, renewals, grace, pickup deadline, fine rates (PUT = LIBRARY_ADMIN)
- `POST /circulation/loans` + `POST /circulation/returns` — desk issue/return by **barcode + membership-number scan** (staff); `POST /circulation/loans/{id}/renew` (member or staff, policy-limited, blocked by waiting reservations)
- `GET /circulation/loans/me` · `GET /circulation/members/lookup?number=MBR-…` — member card / QR-card desk lookup (staff)
- `POST /circulation/reservations` → `POST …/{id}/ready` → `POST …/{id}/collect` (→ loan) · `…/cancel`; queue positions, hold shelf `GET …/reservations/ready`; missed pickup deadlines **expire lazily** (committed even when the triggering call fails)
- `POST/GET /circulation/fines` — LATE auto-assessed on return; LOST/DAMAGED/REPLACEMENT/MANUAL by staff; `…/{id}/pay` (desk settlement) / `…/{id}/waive` (admin only)
- `GET/POST /tenant/notifications/...` — in-app feed (email mirror on every event)

Reservations are a **plan feature** — STARTER libraries answer `403 FEATURE_NOT_IN_PLAN`; circulation tables under RLS via `V8__RlsPoliciesCirculation` on Postgres.

## Phase 6 API quick map

- `GET /api/v1/public/libraries/{slug}/books[?q=]` · `GET …/books/{bookSlug}` — **no auth**: public catalog + public book page payload (only ACTIVE, directory-visible libraries; only `published` books; `shareUrl` built from `app.public-base-url`)
- Member actions: `PUT/DELETE/GET /tenant/catalog/books/{id}/interest` (idempotent "I want this"; signals the author when `author_user_id` is set), staff demand list `GET …/interest/members`
- Physical sales: `POST /tenant/catalog/books/{id}/purchase` (only when `salePriceMinor` set, price snapshot; one pending per member), staff queue `GET /tenant/purchases`, handover `POST …/{id}/fulfill?barcode=…` (sold copy → `REMOVED`, never deleted), `…/cancel`
- Mobile: `/book/:library/:bookSlug` deep-link page (public), `/catalog` + `/catalog/:bookId` member pages with reserve/interest/buy actions

## Phase 7 API quick map

- Uploads (multipart `file`, sniffed — spoofed content-types are rejected): `POST /tenant/catalog/books/{id}/cover` (staff) · `POST /tenant/library/{logo|banner}` (admin) · `POST/DELETE /tenant/profile/avatar`
- Media bytes: `GET /tenant/media/{id}[?variant=thumb]` (members; avatars included) · `GET /api/v1/public/media/{id}` (no auth; covers/logos/banners only, ACTIVE+visible library; avatars 404)
- Ratings: `PUT/DELETE/GET /tenant/catalog/books/{id}/rating` — upsert, idempotent delete, aggregates on the book
- Reviews: `POST /tenant/catalog/books/{id}/reviews`, member feed `GET …/reviews`, own `PATCH/DELETE /tenant/reviews/{id}` (404 for others'); staff `GET /tenant/reviews/moderation?status=` · `POST …/approve` · `…/reject {note}`; reports `POST …/report {reason}` + staff `…/reports/dismiss`; helpful `PUT/DELETE …/helpful`
- Policy: `GET/PUT /tenant/review-policy` — LIBRARY_ADMIN toggles `allowAnonymousReviews` (off → anonymous posts 403)
- Public: `GET /api/v1/public/libraries/{slug}/books/{bookSlug}/reviews` — approved only, anonymous stays anonymous
- Quota: every upload hard-checked against `plan.limits.storageGb` (403 `PLAN_LIMIT_EXCEEDED`); per-file cap 8 MB (413 `FILE_TOO_LARGE`); storage impl by `app.storage.provider` (`memory` default · `minio` w/ `MINIO_*` envs)
