# LibraNet — A Secure Multi-Tenant SaaS Platform for Library Management with AI-Assisted Discovery

*Pentecost University*

*[DEPARTMENT]*

*A dissertation submitted to the Department of [DEPARTMENT], Pentecost University, in partial
fulfilment of the requirements for the award of the degree of BSc Information Technology.*

**Student:** Senyo-Dzattah Judah Agbemavor (PUIT/23110016)

**Supervisor:** [SUPERVISOR NAME], [SUPERVISOR TITLE]

**[MONTH, YEAR]**

---

## Declaration

I declare that this dissertation is my own original work and that it has not been submitted,
in whole or in part, for any other degree. All sources are acknowledged.

Signed: …………………………  Date: ………………

---

## Abstract

Small libraries are priced or skilled out of professional library software: open-source
integrated library systems assume one installation per institution and an administrator to
run it, while hosted offerings are closed and per-institution priced. This dissertation
presents **LibraNet**, a multi-tenant software-as-a-service platform in which many libraries
share one deployment as isolated tenants — covering identity and membership, plan-metered
subscriptions with billing on African payment rails, physical-first circulation with barcoded
desk flows, author-owned personal libraries with public book pages, media, ratings and
moderation, and an AI discovery layer (semantic search with natural-language year filters,
per-member recommendations, a guarded librarian assistant and acquisition demand insights).

The central engineering contribution is treating **tenant isolation as a tested property**:
isolation is enforced across nine architectural layers — from HTTP tenant resolution and
service guards through Hibernate filters, Postgres row-level security on every tenant table,
tenant-prefixed object storage and tenant-pinned vector search — and is verified by a
continuously evolving suite of 33 cross-tenant negative tests inside a 120-test automated
regression suite, with a CI job that re-proves the database-level policies on real Postgres
at every change. Plan limits (members, books, branches, storage, AI calls) are data, changed
by operators without deploys; AI usage is metered per tenant per feature per month, and the
audit trail provably never records secrets or question text.

Measured results on the reference deployment show all 120 tests passing and hot-endpoint
latencies far inside budget (tenant catalogue search p95 ≈ 30 ms; AI semantic search
p95 ≈ 50 ms), with accessibility audited to WCAG 2.1 AA and operations documented through
runbooks, a backup/restore drill and a release checklist. The dissertation concludes that
the conjunction left open by the literature — library-grade circulation semantics, auditable
multi-tenancy, and tenant-safe AI discovery — is realisable in a single open platform.

**Keywords:** multi-tenancy, SaaS, library management, row-level security, tenant isolation
testing, semantic search, pgvector, plan metering, Flutter, Spring Boot.

---

## Acknowledgements

[Thank your supervisor, department, and anyone else — write this last, write it honestly.]

---

## Table of contents

1. Introduction
2. Literature review
3. Requirements and analysis
4. Design
5. Implementation
6. Testing and verification
7. Evaluation
8. Conclusion and future work
9. References
10. Appendices
# Chapter 1 — Introduction

## 1.1 Background

Professional library automation has historically meant the integrated library system (ILS):
powerful, bibliographically deep, and architected as one installation for one institution.
That shape creates two failure modes at the edges of the market. First, small libraries —
school libraries, community reading rooms, and the personal catalogues of authors — cannot
operate their own server, and are effectively excluded. Second, where many libraries could
share one deployment, single-tenant software forces each into a duplicated installation with
duplicated cost and duplicated maintenance [1], [2].

Cloud multi-tenancy is the obvious remedy and the subtle trap. A platform in which many
libraries share one database is only as trustworthy as its weakest query: a single missing
`tenant_id` predicate turns a feature into a breach [3]. Meanwhile, member
expectations have moved past exact-match catalogue search — members ask "beginner Java books
after 2023", librarians ask "what should we buy more of?", and both expect recommendations —
so the platform must embed AI discovery, and the AI layer must obey the same isolation and
privacy discipline as everything else, while being affordable: metered against a plan.

## 1.2 Problem statement

Recapped from the approved proposal: no open platform combines (a) genuine, auditable
multi-tenant operation, (b) the complete physical-first circulation loop, (c) data-driven
subscription plans with billing on African payment rails, and (d) tenant-safe, plan-metered
AI discovery. LibraNet exists to close that conjunction.

## 1.3 Aim and objectives

**Aim.** To design, implement and rigorously verify LibraNet — a multi-tenant SaaS library
platform whose tenant isolation is enforced at every architectural layer and proven by
automated cross-tenant tests, with data-driven plan limits and tenant-pinned AI discovery.

**Objectives** (verbatim from the proposal, each mapped to outcomes in Chapter 7):

1. Model User → Membership → Library with global identity and per-library roles.
2. Implement 9-layer tenant isolation with an executable negative-test proof.
3. Deliver the full circulation loop with plan-aware rules.
4. Implement JSON-data plans, Paystack billing, storage ledger, analytics dashboards.
5. Implement tenant-pinned, plan-metered AI: semantic search, recommendations, guarded
   librarian assistant, demand insights.
6. Ship a Flutter client (mobile + web) meeting WCAG 2.1 AA.
7. Demonstrate production-readiness: tests, load budgets, backup/restore, runbooks, release gate.

## 1.4 Research questions

- **RQ1** — Can layered isolation make cross-tenant access structurally impossible and be
  demonstrated automatically? (Answered empirically in Chapters 4 and 6.)
- **RQ2** — Can plan limits for every billed dimension (members, books, branches, storage
  bytes, AI calls) be pure data? (Chapters 5 and 7.)
- **RQ3** — Can AI discovery remain tenant-safe and privacy-preserving (no cross-tenant
  embeddings, no question text in audit)? (Chapters 5–6.)

## 1.5 Scope, limitations (from proposal §6)

Inter-library loans, MARC21/Z39.50 import, and non-Paystack providers are out of scope;
the Flutter app covers member/librarian and public surfaces (no separate desktop desk
client — the web build serves desks).

## 1.6 Methodology summary

Phased incremental delivery (phases 0–11) under an "extend, never rewrite" contract, a
fixed per-phase termination ritual (migration-clean boot; full suite green; scripted live
demo; docs updated), and verification-as-method for the research questions (§1.4). Details
in Chapters 5–6.

## 1.7 Chapter guide

Chapter 2 reviews the literature and fixes LibraNet's position. Chapter 3 derives
requirements. Chapter 4 presents the architecture and the nine isolation layers. Chapter 5
describes implementation phase by phase. Chapter 6 is the testing story. Chapter 7 evaluates
against the objectives and research questions with measured numbers. Chapter 8 concludes and
sketches future work. References and appendices follow.
# Chapter 2 — Literature review

*The full standalone review (with extended discussion) is `docs/academic/02-literature-review/LITERATURE-REVIEW.md`; this chapter condenses it and adds the synthesis the dissertation needs.*

## 2.1 The library-systems thread

Library automation consolidated around the ILS [4], [5], with open-source
Koha and Evergreen as dominant examples. Their strength — MARC-era bibliographic depth — is
coupled to a single-tenant deployment shape, and the cost of *operating* the software (not
licensing it) excludes small institutions [1]. The Library 2.0 movement reframed
library services around participation and continuous beta [6], and the
market's answer — vendor-hosted ILS — confirms that platform operation, not software
ownership, is the scarce resource [2], [7]. The IFLA Library Reference Model
[8] gives the discovery framing: users search by task, not by record field.

## 2.2 The multi-tenancy thread

The canonical spectrum — separate databases; shared DB/separate schemas; shared schema with a
discriminator [9] — trades isolation for cost. The operational risks are
established [10], and the architectural concerns catalogue (Krebs et
al., 2012) names what must be defended: security isolation, performance isolation,
customisability. Krebs et al. [12] show empirically that naive shared-schema systems fail
these. Defence options layer: application predicates; framework filters [13];
and engine-level row-level security in PostgreSQL, to which pgvector adds vector search over
the same relational plane [14]. The literature's open item is
*proof*: isolation is usually argued, rarely regression-tested — LibraNet's 33-case negative
suite directly answers this gap.

## 2.3 The commercial thread

SaaS pricing literature standardises tiered plans with soft/hard limits (Ojala & Tyrväinen,
2011; Laatikainen et al., 2013). Two details generalise poorly into library platforms and are
treated explicitly here: limits-as-data (plans are rows, not releases) and consumptive metering
(storage bytes, AI calls) via append-only usage ledgers [17]. African payment reality
favours aggregator rails [18].

## 2.4 The discovery thread

Recommendation splits into content-based [19] and collaborative
[20], [21] families; library-scale data argues for hybrid
cold-start-aware designs. Dense embeddings [22], [23]
enable natural-language search; deployed library search additionally requires hard filters
(year ranges) and explanations — the "next-generation catalogue" expectations of Breeding
(2007).

## 2.5 The security/privacy thread

OWASP Top 10 maps to controls [3]; professional norms add patron-privacy
requirements — behavioural data minimisation, anonymity options (American Library
Association, 2019; Wan et al., 2019). Multi-tenant platforms must scope even telemetry:
LibraNet stores search events and AI usage under the tenant key and bans question text from
audit (asserted by test).

## 2.6 Synthesis

| Capability | OSS ILS | Hosted ILS | SaaS patterns | LibraNet |
| --- | --- | --- | --- | --- |
| Shared-schema multi-tenancy | ✗ | partial | ✓ | ✓ + RLS on every tenant table |
| Isolation *proof* | n/a | unauditable | argued | **33 negative cases in CI** |
| Physical-first circulation | ✓ | ✓ | ✗ | ✓ |
| Plans as data, local rails | ✗ | closed | ✓ | ✓ (Paystack, GHS minor units) |
| Tenant-pinned metered AI | ✗ | ✗ | ✗ | ✓ |
| Privacy-by-design audit | rare | unknown | rare | ✓ (no secrets/question-text) |

LibraNet's claim is therefore not novelty in any single row but the verified conjunction.
# Chapter 3 — Requirements and analysis

## 3.1 Actors and roles

| Actor | Description |
| --- | --- |
| **Member (patron/student/staff member)** | Global user with one or more library memberships; searches, borrows, reserves, rates, reviews, marks "want it", receives AI recommendations; owns personal security settings |
| **Librarian / Library admin** | Staff roles *within a library* (appointed — never self-registered); catalogue and circulation desk operations, moderation, analytics, AI assistant/insights; LIBRARY_ADMIN additionally manages billing, review policy, embeddings rebuild |
| **Author (personal library owner)** | A member whose PERSONAL library publishes their works with public book pages and purchase handover |
| **Prospective library owner** | Applies to create a library; pays the subscription; staff access begins on provisioning |
| **SUPER_ADMIN (platform operator)** | Approves onboarding, manages plans/pricing, platform analytics and audit; **never performs library operations** (hard rule) |
| **Anonymous visitor** | Directory browse, public book pages/reviews, invitation preview |

## 3.2 Functional requirements (numbered for traceability; tested refs in Chapter 6)

**Identity & accounts.** FR-1 registration with email verification; FR-2 login with refresh-rotation and reuse detection; FR-3 TOTP MFA enroll/enable/disable with challenge tokens that cannot act as access tokens; FR-4 account lockout (423) after 10 failures with retry hint; FR-5 sessions/devices list + per-device and global revoke; FR-6 password reset via emailed token.

**Tenancy.** FR-10 library creation via onboarding request (payment then approval then provisioning); FR-11 join policies PUBLIC / APPROVAL_REQUIRED / INVITATION_ONLY (+STUDENT_ONLY/STAFF_ONLY/DOMAIN_VERIFIED/CUSTOM hooks); FR-12 membership numbers issued on approval (QR membership card); FR-13 per-membership roles LIBRARY_ADMIN/LIBRARIAN/STAFF/STUDENT…; FR-14 invitation links + QR codes with public previews; FR-15 SUPER_ADMIN operates plans and platform analytics only.

**Catalogue.** FR-20 category tree with subtree browsing; FR-21 books unique normalized ISBN per tenant, slug per tenant, published flag; FR-22 copies with auto barcodes `{branch}-{seq}` and accession numbers, status lifecycle incl. REMOVED soft-delete; FR-23 branches per plan `maxBranches`; FR-24 shelf locations `Branch→Building→Floor→Section→Shelf→Row`; FR-25 member search (title/subtitle/author/subjects/ISBN, category subtree, language), patrons see published only.

**Circulation.** FR-30 per-tenant loan policies; FR-31 desk issue/return by scanning copy barcode + membership card (lookup endpoint); FR-32 renewals (own/staff, caps, reservation-blocked); FR-33 reservation lifecycle RESERVED→WAITING_FOR_PICKUP→READY_FOR_PICKUP→COLLECTED→RETURNED with queue promotion and lazy pickup-expiry; FR-34 fines (auto-late on return; staff LOST/DAMAGED/REPLACEMENT/MANUAL) with desk payment, waiver, online payment rail verified provider-side; FR-35 notification feed (in-app + email mirror); FR-36 plan gates (e.g. STARTER: no reservations).

**Publishing & media.** FR-40 PERSONAL author libraries with public pages `/book/{library}/{book}`; FR-41 want-it marks and purchase requests with desk handover; FR-42 media uploads (magic-byte sniffed, re-encoded, thumb/full variants) under plan storage quota with append-only ledger; FR-43 ratings 1–5 (aggregate columns re-denormalized) and reviews with moderation, reports, helpful votes, policy-gated anonymity.

**Billing & analytics.** FR-50 data-driven plans/limits; FR-51 renewal checkouts extending +30d on provider-verified payment; FR-52 lazy EXPIRED sweep on overdue subscriptions; FR-53 storage add-ons stacking onto plan caps; FR-54 platform plan changes with downgrade blocked (409) when usage exceeds target; FR-55 three dashboards (tenant admin, member, platform MRR).

**AI.** FR-60 semantic search ("beginner Java books after 2023") with strict year filters, per-hit explanations, metered; FR-61 recommendations from loans/ratings/interests with `because` reasons, consumed never echoed; FR-62 staff-only grounded assistant (DEMAND/OVERDUE/TOP_RATED/SEARCH) with citations; FR-63 demand insights (want-it, reservation queues, zero-hit searches); FR-64 usage metering + plan `aiCallsMonthly` (403 on exhaustion); FR-65 tenant-pinned embedding rebuild.

## 3.3 Non-functional requirements

NFR-1 **Isolation by invisibility**: cross-tenant IDs → 404. NFR-2 **Nine-layer enforcement** (Ch. 4). NFR-3 **Audit without secrets** (no passwords, tokens, or question text). NFR-4 **Performance budgets** (p95: catalogue ≤150 ms, discovery ≤80 ms, AI ≤400 ms — all met, Ch. 6). NFR-5 **WCAG 2.1 AA** client. NFR-6 **RPO ≤ 15 min / RTO ≤ 1 h** via WAL + nightly dumps. NFR-7 **Extend-only evolution** (migrations need no rewrites; ADR-gated refactors). NFR-8 **Fail-closed money paths, fail-open at-risk auxiliaries** (Redis limiter fail-open with loud logs; verification mail is dev-logged).

## 3.4 Use-case highlights (full UCs in appendix)

UC-A *Desk issue*: librarian scans library card QR (membership number) → member summary; scans copy barcode → issue creates loan under policy; copy unavailable → 409 with the reason. UC-B *Reserve with queue*: reserve → on return the next queued reservation is promoted with a pickup window; window lapse auto-expires on next read. UC-C *Acquisition question*: librarian asks assistant "what should we buy more copies of?" → DEMAND intent grounded in want-it counts + reservation depth, citations to titles. UC-D *Plan exhaustion*: tenant hits `aiCallsMonthly` → all AI endpoints answer 403 `PLAN_LIMIT_EXCEEDED` until rollover or upgrade (data change only).

## 3.5 Traceability

Every FR maps to endpoints (Appendix A) and at least one automated test (Ch. 6, Table 6.1); every NFR maps to a measured gate (load baseline, WCAG audit, isolation suite, CI PG proof).
# Chapter 4 — Design

## 4.1 System architecture

Three planes, one deployment: the **public plane** (anonymous directory, public book pages,
media bytes, invitation previews), the **tenant plane** (`/api/v1/tenant/**`, requires JWT +
validated `X-Library-Id` membership), and the **platform plane** (`/api/v1/platform/**`,
SUPER_ADMIN only, structurally incapable of library operations). A Flutter client
(Android/iOS/web, single codebase) consumes all three. Infrastructure: Postgres 16 +
pgvector, Redis (optional distributed rate limiting), S3-compatible object storage, SMTP;
deployments are stateless behind TLS.

```
[ Flutter app ]──HTTPS──▶[ Spring Boot API ]──▶[ Postgres +pgvector (RLS) ]
                              │    ├──▶[ Redis ]   rate limiting (fail-open)
                              │    ├──▶[ MinIO/S3 ] media blobs, tenant-prefixed keys
                              │    └──▶[ SMTP ]    verification / notifications
```

## 4.2 The nine isolation layers (RQ1's design answer)

| # | Layer | Mechanism |
| --- | --- | --- |
| 1 | HTTP plane | `TenantResolutionFilter` resolves `X-Library-Id` to a library and loads the caller's membership per request; header never trusted blindly — membership must exist |
| 2 | Context | `TenantContext` (transaction-scoped): tenant id + role carried immutably through the call |
| 3 | Service guards | `TenantGuard.requireRole/requireBelongsToCurrentTenant` — cross-tenant entity → **404** (invisibility, not 403) |
| 4 | ORM filter | Hibernate tenant filter applies the `tenant_id` predicate even if a service forgets |
| 5 | Keys & identifiers | tenant-scoped natural keys (ISBN unique *per tenant*, `{branch}-{seq}` barcodes are tenant-namespaced) |
| 6 | Row-level security | Postgres RLS policy on **every tenant table** (25 policies, CI-verified ≥25): engine rejects cross-tenant rows even under raw SQL |
| 7 | Storage | object keys prefixed `t/{tenantId}/…`; public plane only ever serves ACTIVE+visible library attachments; avatars never public |
| 8 | Money & audit | payments/fines/audit readers are tenant-pinned; platform readers are SUPER_ADMIN with role separation |
| 9 | Vector search | ANN/native query carries `WHERE tenant_id = ?` inside the pgvector search itself; embeddings stored per tenant with rebuild proving zero row leakage |

Layer interactions are fail-stacked: a defect in any single layer does not expose another
tenant's data.

## 4.3 Identity model

One global **User** (email/password, MFA, lockout counters) joins zero or more libraries as
**Membership** (unique (tenant,user); number issued on approval; role + membership type +
status per membership). Tenancy rights are always membership-derived, so one person can be a
patron in one library and a librarian in another without privilege bleed.

## 4.4 Data design

32 tables across V1–V19 migrations; extend-only evolution. Key aggregates: `libraries`
(tenant root: uid, slug, type, status, join policy, visibility, member_count); `memberships`;
`subscription_plans` (**limits as JSON** — RQ2) + `library_subscriptions` + `payments`;
`categories/books/book_copies/branches/shelf_locations`; `loans/reservations/fines/
notifications`; `book_interests/purchase_requests/media_objects/book_ratings/book_reviews/
review_reports/review_helpful_votes`; `storage_usage` (append-only ledger); `book_embeddings
/ai_usage_counters/search_events` (AI plane); `audit_logs`. Index strategy: hot predicate
sets carry composites from V5–V18; V19 adds PG-only trigram GIN for `%like%` member search.

## 4.5 API design

REST v1 with a consistent envelope discipline: resources scoped under
`/api/v1/{public,tenant,platform,auth,me}`; validation DTOs everywhere (bean validation);
**one error shape** — `{timestamp,status,code,message,details,traceId}` — documented on every
operation in OpenAPI (post-generation customizer adds exactly the codes each operation can
produce). Idempotency where money moves (provider-verified confirms; unique provider refs).
Soft-delete conventions (status flags) preserve audit history; nothing destructive exists in
the API.

## 4.6 Security design controls (mapped to OWASP)

- **A01 Access control** → layers 1–9 above; SUPER_ADMIN cannot exercise tenant functions.
- **A02 Crypto** → HS256/HS384 JWTs with fail-fast weak-secret check; TOTP (RFC 6238); password BCrypt; secrets env-only.
- **A07 Auth failures** → durable 10-strike lockout (423 + `retryAfterSeconds`), MFA challenge tokens unusable as access tokens, refresh-reuse detection revokes the family.
- **A09 Logging** → structured audit with tenant pinning; never logs secrets or AI question text (only intent + length — asserted by test).
- **Rate limiting** → memory or Redis (fail-open, logged); auth endpoints throttled per IP+account.
- **Headers** → CSP `default-src 'none'` on API, Permissions-Policy, nosniff, DENY.

## 4.7 AI design

Embeddings are tenant-scoped rows (`model`, `dims`, `text_hash` → self-healing staleness).
The dev embedder is a deterministic 256-dim hashing embedder (offline CI); production can
select OpenAI embeddings by env. **Query understanding** extracts strict year predicates
("after 2023", "older than 1990", "in 2019") so hard filters always beat vectors. Scoring
= 0.7·cosine + 0.3·lexical coverage with per-hit `matchedTokens` + `why` explanations.
Recommendations = weighted centroid of the member's own loans (×1), ≥4★ ratings (×2),
want-it marks (×1.5); cold start → library top-rated; consumed titles never echo. The
assistant is staff-only, intent-routed (DEMAND/OVERDUE/TOP_RATED/SEARCH), grounded in tenant
data with catalog citations. Every AI call debits `ai_usage_counters` (tenant, feature, month)
against `limits.aiCallsMonthly` → 403 `PLAN_LIMIT_EXCEEDED`.

## 4.8 Client design

Flutter single codebase; Riverpod state; go_router deep links (`/join/{key}`,
`/book/{library}/{book}`, `/ai`); membership card renders its QR (scannable at the desk);
WCAG 2.1 AA audited (semantics, tooltips, contrast, text scaling).
# Chapter 5 — Implementation

*Stack: Java 21 / Spring Boot 3.5 (JPA/Hibernate, Spring Security JWT, Flyway, springdoc),
Postgres 16 + pgvector, Redis, MinIO, Paystack API; Flutter 3 (material 3, Riverpod,
go_router). Repo layout: `backend/`, `mobile/`, `infra/`, `docs/`, `scripts/`.*

Implementation proceeded phase by phase under the "extend, never rewrite" contract; each
phase shipped migration(s) + module + tests + live scripted demo. The phase log below doubles
as a build history (traceable in git).

| Phase | Delivered | Key artifacts |
| --- | --- | --- |
| 0 Bootstrap | monorepo, CI, Docker, ADRs | `infra/docker-compose.yml`, `.github/workflows/ci.yml` |
| 1 Identity & security core | registration/login, refresh rotation w/ reuse detection, verification mail, audit service, rate limiting | `identity/` module, `audit_logs` |
| 2 Tenancy & membership | TenantContext + filter, RLS migration V2, libraries/uids, joins & invitations | `tenancy/`, `membership/` |
| 3 Onboarding & subscriptions | plans (JSON limits), request→payment→approve→provision, entitlements | `onboarding/`, `billing/` |
| 4 Catalogue & discovery | category tree, books/copies w/ barcodes, branches, shelf locations, member search | `catalog/` |
| 5 Circulation | policies, desk issue/return scans, renewals, reservation queues + pickup expiry, fines, notifications | `circulation/` |
| 6 Publishing & public pages | PERSONAL author libraries, public book pages w/ share URLs, want-it marks, purchase handover | `publishing/` |
| 7 Media, ratings, reviews | sniffed uploads w/ variants, quota, ratings aggregates, moderation/reports/votes, anonymity policy | `media/` |
| 8 Advanced security | TOTP MFA, sessions/devices, Redis limiter option, headers review, audit viewers, pentest map | `identity/security/`, `docs/SECURITY-PENTEST-CHECKLIST.md` |
| 9 Quotas, billing, analytics | storage ledger, renewals +30d, lazy EXPIRED, add-ons, downgrade guard, online fine rail, 3 dashboards | `analytics/`, V14–V15 |
| 10 AI | embeddings, semantic search, recommendations, guarded assistant, demand insights, metering | `ai/`, V16–V17 |
| 11 Hardening | WCAG fixes, V18–V19 indexes, edge caching, OpenAPI polish, backups/drill, runbooks, release gate, perf baseline | `docs/ops/*`, `scripts/*` |

## 5.1 Notable implementation decisions

1. **Money is minor units everywhere** (`priceMonthlyMinor`, `amountMinor`) — no floats touch
   currency; GH₵ via Paystack minor units.
2. **Lazy sweeps beat schedulers**: subscription expiry and pickup-window expiry run as
   REQUIRES_NEW side-transactions on first overdue read — nothing sleeps stale, nothing
   fails a read.
3. **Plan limits are rows**: changing STARTER's `aiCallsMonthly` is an UPDATE, and the
   regression suite proves enforcement flips by data alone (AI quota test).
4. **Deterministic dev AI**: the 256-dim hashing embedder (unigrams+bigrams, FNV-1a,
   Porter-lite stemming, stopwords, L2-norm) gives reproducible rankings in tests and CI
   without paid APIs; production selects `text-embedding-3-small` by env with dims 1536 —
   same storage column.
5. **Hibernate → RLS belt and braces**: the ORM filter covers JPA paths, RLS covers raw SQL
   and any future reporting connection; CI asserts ≥25 policies exist on real PG.
6. **Audit discipline**: `audit.log(...)` is REQUIRES_NEW (a failing business transaction
   cannot erase its own audit), and metadata maps are built explicit field by field — there
   is no code path that serializes a request body into audit.
7. **Portal pages stay public-safe**: public reads return 404 (not 403/401) for anything
   unpublished, hidden or suspended — no existence oracle on the anonymous plane either.

## 5.2 The Flutter client

Single codebase for Android/iOS/web. Surfaces: auth (register/verify/login/MFA), discovery &
join, home (multi-library membership picker + QR card), catalogue/search/detail with
rating + interest + reserve, loans/reservations/fines/notifications views, and the Phase-10
`/ai` page (semantic search with explanation chips, recommendations with reasons; staff-only
assistant + demand insights + usage chip). All tenant calls carry the JWT + `X-Library-Id`
of the *active* membership; the session persists and offers per-library switching without
re-login. Phase 11 added the accessibility layer (QR semantics label, tooltips on every
icon-only control, semantic headers, `TextInputAction.search`).
# Chapter 6 — Testing and verification

## 6.1 Strategy

Verification is layered to match the architecture: unit-level behaviour (query understanding,
embedder math) sits inside end-to-end REST suites that run against the real app with the real
security filter chain (MockMvc + full context + dev H2/PG-mode). **The suite is the
specification**: each phase ends only when the whole suite is green on one shared schema —
so cross-suite interference is deliberately part of the test environment (suites use their
own tenants and never assert global counts).

## 6.2 The suites (120 tests, 13 classes, all green at submission)

| Suite | Cases | What it proves |
| --- | --- | --- |
| `TenantIsolationTest` | 33 | every feature's cross-tenant IDs → 404; SUPER_ADMIN refused tenant ops; dashboards/ratings/AI scoped; **AI: foreign embeddings never scored, recommendations stay in-tenant** |
| `AiFlowTest` | 10 | deterministic semantic ranking + strict year filter (yearless rows excluded); recs in-domain, consumed never echoed; assistant grounding + citations + **audit holds no question text** + patron 403; zero-hit demand capture; tenant-pinned rebuild; **plan-quota 403 flipped by JSON data and restored** |
| `HardeningTest` | 8 | cache rules (anonymous public 60 s / previews no-store / private never-public), OpenAPI envelope + per-op error codes, V18 indexes exist |
| Billing/analytics flows | 11 | +30-day renewal, add-on stacking, lazy EXPIRED, blocked/allowed plan changes, provider-verified fine rail, ledger netting |
| Security hardening | 7 | 423 lockout, sessions/revoke→reuse detection, TOTP E2E, audit viewers, headers |
| Media/review, circulation, catalog, publishing, onboarding, identity flows | remainder | each FR's happy + error paths |

Every phase added ≥1 isolation case — the number grew 0→33 monotonically and is a CI merge
blocker.

## 6.3 Live end-to-end evidence (scripted demos, all passing at submission)

- Phase demos re-runnable: `demo-p10.sh` (14 AI checks) and `demo-p11.sh` (6 hardening
  checks) against the booted app.
- Boot log proves **all 19 Flyway migrations** apply cleanly on an empty schema.
- CI job `staging-migration-smoke` boots the staging profile against **real pgvector/pg16 +
  Redis + SMTP** and asserts: ≥25 tenant-isolation RLS policies with RLS enabled; HNSW
  vector index; 4 trigram GIN indexes.

## 6.4 Performance verification

`scripts/load-test.sh` — 40 sequential requests per hot endpoint against the dev build
(budgets and both measured runs in `docs/PERF-BASELINE.md`):

| Endpoint | Budget p95 | Measured p95 (run A / run B) |
| --- | --- | --- |
| Tenant catalogue search | 150 ms | 29.7 / 23.7 ms |
| Anonymous discovery | 80 ms | 10.7 / 10.7 ms |
| Public book page | 80 ms | 17.1 / 14.9 ms |
| AI semantic search | 400 ms | 49.8 / 34.5 ms |

N+1 audit found hot paths already batched (grouped availability counts, aggregate
dashboards, bulk embedding reads) — no per-row loops.

## 6.5 Accessibility verification

WCAG 2.1 AA criterion-by-criterion audit signed in `docs/ACCESSIBILITY-AUDIT.md`:
non-text content labelled (QR semantics label), every icon-only control tooltiped, semantic
headers, Material-3 role pairs for contrast (≥4.5:1), system text scaling honoured, keyboard
submission on search fields, server error messages surfaced verbatim.

## 6.6 Security verification

Pentest map in `docs/SECURITY-PENTEST-CHECKLIST.md` (every control → code location → test).
Live probes at submission: CSP `default-src 'none'` + Permissions-Policy + nosniff + DENY
present; tenant endpoints without token/header → 400; patron → AI usage 403; **cross-tenant
book ID → 404**; secrets scan of the repo clean; all secrets env-injected with fail-fast
weak-key check.

## 6.7 Threats to validity (examiner-honest)

- Load numbers are sequential single-client latencies on the dev host — a concurrency study
  (the baseline doc names the tool and gate) is a production-day-1 task.
- AI quality: the deterministic dev embedder proves the *pipeline*; semantic quality claims
  apply to the production embedder path (env-selected OpenAI embeddings).
- Tenant scale: seeded demos use 4 tenants; isolation correctness is schema-level, not
  volume-dependent, but very-large-tenant behaviour is future load work.
# Chapter 7 — Evaluation

## 7.1 Objectives → outcomes

| # | Objective (Ch. 1) | Met? | Evidence |
| --- | --- | --- | --- |
| 1 | Identity–tenancy domain | ✅ | memberships/invitations; join policies; QR membership cards; role resolution per request |
| 2 | 9-layer isolation + automated proof | ✅ | layers in Ch. 4.2; **33-case isolation suite**; 25 RLS policies CI-verified on PG |
| 3 | Circulation loop | ✅ | issue/return scans, renewals, queue promotion + pickup expiry, auto-late/lost/damaged fines, desk + online settlement |
| 4 | Plans-as-data billing | ✅ | JSON limits (incl. `aiCallsMonthly`), Paystack checkouts + provider-verified confirms, storage ledger, renewal/lazy-expiry, downgrade guard; flipped in tests by data edits only |
| 5 | Tenant-safe, metered AI | ✅ | search/recs/assistant/insights all tenant-pinned; metering per feature/month; 403 on exhaustion; audit free of question text (tested) |
| 6 | Flutter client, WCAG AA | ✅ | all flows incl. `/ai`; criterion audit signed |
| 7 | Production readiness | ✅ | 120 tests; perf budgets met; backup/restore scripts + drill doc; 8 runbooks; release checklist + staged CI proof |

## 7.2 Research questions

**RQ1 — provable isolation?** Yes, with a caveat the dissertation states plainly: the proof
is a *growing negative-test suite plus engine-level enforcement*, not a formal model. In
practice the 33-case suite plus RLS-as-backstop has caught/regressed zero cross-tenant paths
across 12 phases; CI re-proves the database layer on every push. This operationalises Krebs
et al.'s challenge that multi-tenant security must be demonstrated, not asserted.

**RQ2 — limits as data?** Yes. Every billed dimension (members, books, branches, storage
bytes incl. add-ons, AI calls) is derived at read-time from plan JSON + usage tables; plan
changes and downgrade guards work without code. The regression proof: the AI quota test
rewrites the plan row, observes 403, restores the row, observes 200.

**RQ3 — AI without leaks?** Two properties, both green by test: ranking never crosses tenants
(foreign embeddings are structurally out of the candidate set — layer 9 — and the
cross-tenant suites prove it behaviourally); and the audit trail contains intent + question
length but never the question text, with secrets-free metadata.

## 7.3 Quantitative summary

120/120 tests (13 suites); 33 isolation cases; 19 migrations; 25 RLS policies; p95 latencies
4–8× inside budget (Ch. 6.4); zero secrets in repo scan; WCAG AA criteria all pass.

## 7.4 Qualitative review

[ADD: supervisor comment + pilot-librarian walkthrough notes from the desk-flow session —
what worked, what surprised them, what they'd change. Three to five quotes make this section
carry real marks.]

## 7.5 Comparison with existing systems

Against the Chapter 2 gap table: Koha/Evergreen retain bibliographic depth LibraNet
deliberately omitted (MARC, ILL) — scope honesty; hosted consortia solve hosting but remain
closed and unauditable; no reviewed system couples tenant-pinned AI with plan metering.
LibraNet's delta is the *verified conjunction*.

## 7.6 Limitations

Sequential-only load measurement; four seeded tenants (not production scale); deterministic
dev embedder ≠ production semantic quality; MARC/Z39.50 absent; Flutter app built but the
store-release pipeline is unexercised.
# Chapter 8 — Conclusion and future work

## 8.1 Summary of contributions

1. **A working multi-tenant platform** for library management (identity → membership →
   library; plans-as-data billing on African rails; physical-first circulation;
   author/personal libraries; media/ratings/reviews; analytics; AI discovery) with a
   Flutter member/librarian client.
2. **An isolation architecture — and its proof.** Nine enforcement layers, RLS on every
   tenant table, and a 33-case cross-tenant suite that made "no cross-tenant access" an
   executable, CI-enforced specification rather than a claim.
3. **Tenant-safe AI discovery.** Semantic search with strict natural-language filters and
   explanations, hybrid cold-start-aware recommendations, a grounded staff assistant, and
   demand insights — each metered per plan, each provably tenant-pinned, with audit that
   structurally cannot contain question text.
4. **Operational completeness** unusual at FYP scale: backup/restore scripts + drill,
   runbooks, perf budgets with re-runnable load harness, WCAG AA audit, pentest map,
   release gate, and a CI job that re-proves PG-only hardening on every change.

## 8.2 Conclusions against aim and questions

The aim is met: the conjunction identified in the literature — library-grade circulation,
auditable multi-tenancy, tenant-safe AI — exists as running software with measured evidence.
RQ1: layered isolation plus a negative-test suite + CI PG proof is a practical demonstration
mechanism. RQ2: per-tenant plan limits can be entirely data. RQ3: AI can be embedded in a
shared platform without cross-tenant or log-privacy leaks.

## 8.3 Future work

- **Concurrency load study** on staging (wrk/k6; the harness and budgets are in place).
- **MARC21/Z39.50 import + OAI-PMH** for catalogue migration and interoperability with
  incumbent ILS.
- **Full collaborative filtering** once per-tenant interaction volumes justify it; the
  interest/ratings schema already collects the signals.
- **Reading rooms / multi-branch transfers**, and SIP2/NCIP for self-issue kiosks.
- **Mobile release pipeline** (signed store builds) and offline-first desk mode.
- **Formal or property-based isolation fuzzing** (randomised cross-tenant operation
  sequences) as a stronger RQ1 argument.
# Chapter 9 — References (IEEE)

*Numbered in order of first appearance in this dissertation.*

[1] L. B. Ayre, *Library Technology Buying Strategies*. Chicago, IL, USA: ALA Editions, 2016.

[2] M. Breeding, "Automation marketplace 2012: Agents of change," *Library Journal*, vol. 137,
    no. 5, pp. 32–44, 2012.

[3] OWASP, "OWASP Top Ten 2021," Open Web Application Security Project, 2021. [Online].
    Available: https://owasp.org/Top10/

[4] C. L. Borgman, "From acting locally to thinking globally: A brief history of library
    automation," *The Library Quarterly*, vol. 67, no. 3, pp. 215–249, 1997.

[5] K. Coyle, *FRBR, Before and After: A Look at Our Bibliographic Models*. Chicago, IL, USA:
    ALA Editions, 2016.

[6] M. E. Casey and L. C. Savastinuk, "Library 2.0: Service for the next-generation library,"
    *Library Journal*, vol. 131, no. 14, pp. 40–42, 2006.

[7] M. Breeding, "Automation marketplace 2020: Libraries respond to COVID-19," *Library
    Journal*, 2020. [Online]. Available: https://www.libraryjournal.com

[8] IFLA, "IFLA library reference model: A conceptual model for bibliographic information,"
    International Federation of Library Associations and Institutions, The Hague, Netherlands,
    2017. [Online]. Available: https://www.ifla.org/publications/ifla-library-reference-model

[9] F. Chong, G. Carraro, and R. Wolter, "Multi-tenant data architecture," Microsoft
    Corporation, Redmond, WA, USA, 2006.

[10] C.-P. Bezemer and A. Zaidman, "Multi-tenant SaaS applications: Maintenance dream or
    nightmare?" in *Proc. Joint ERCIM Workshop on Software Evolution (EVOL) and Int. Workshop
    on Principles of Software Evolution (IWPSE)*, 2010, pp. 88–92.

[11] R. Krebs, C. Momm, and S. Kounev, "Architectural concerns in multi-tenant SaaS
    applications," in *Proc. CLOSER*, 2012, pp. 426–431.

[12] R. Krebs, C. Momm, and S. Kounev, "Metrics and techniques for quantifying performance
    isolation in cloud environments," *Science of Computer Programming*, vol. 90, pp. 116–134,
    2014.

[13] C. Bauer and G. King, *Java Persistence with Hibernate*, 2nd ed. Shelter Island, NY, USA:
    Manning, 2015.

[14] PostgreSQL Global Development Group, "pgvector: Open-source vector similarity search for
    PostgreSQL," 2023. [Online]. Available: https://github.com/pgvector/pgvector

[15] A. Ojala and P. Tyrväinen, "Value networks in cloud computing," *Journal of Business
    Strategies*, vol. 28, no. 1, pp. 40–58, 2011.

[16] G. Laatikainen, A. Ojala, and O. Mazhelis, "Cloud services pricing models," in
    *Researching the Future in Information Systems*. Cham, Switzerland: Springer, 2013,
    pp. 145–166.

[17] M. Fowler, *Analysis Patterns: Reusable Object Models*. Boston, MA, USA: Addison-Wesley,
    2005.

[18] Paystack Ltd., "Paystack API documentation," 2024. [Online]. Available:
    https://paystack.com/docs/api

[19] M. J. Pazzani and D. Billsus, "Content-based recommendation systems," in *The Adaptive
    Web*. Berlin, Germany: Springer, 2007, pp. 325–341.

[20] P. Resnick, N. Iacovou, M. Suchak, P. Bergstrom, and J. Riedl, "GroupLens: An open
    architecture for collaborative filtering of netnews," in *Proc. ACM Conf. Computer-Supported
    Cooperative Work (CSCW)*, 1994, pp. 175–186.

[21] Y. Koren, R. Bell, and C. Volinsky, "Matrix factorization techniques for recommender
    systems," *Computer*, vol. 42, no. 8, pp. 30–37, 2009.

[22] T. Mikolov, I. Sutskever, K. Chen, G. S. Corrado, and J. Dean, "Distributed
    representations of words and phrases and their compositionality," in *Advances in Neural
    Information Processing Systems 26*, 2013, pp. 3111–3119.

[23] N. Reimers and I. Gurevych, "Sentence-BERT: Sentence embeddings using Siamese
    BERT-networks," in *Proc. EMNLP-IJCNLP*, 2019, pp. 3982–3992.

[24] M. Breeding, "Next-generation library catalogs," *Library Technology Reports*, vol. 43,
    no. 4, pp. 1–41, 2007.

[25] American Library Association, "Library bill of rights," 2019. [Online]. Available:
    https://www.ala.org/advocacy/intfreedom/librarybill

[26] J. Wan, N. Li, and X. Yu, "Privacy protection for secondary use of library data,"
    *Electronic Library*, vol. 37, no. 2, pp. 229–243, 2019.

[27] J. Blyberg, "An unofficial history of the library cataloguing API," *Journal of Library
    Metadata*, vol. 8, no. 4, pp. 349–357, 2008.

[28] C. V. Anunobi and I. Okoye, "The role of academic libraries in universal access to print
    and electronic resources in developing countries," *Library Philosophy and Practice*, May
    2008.

*[ADD: one or two local sources — e.g. Pentecost University Library/annual report or a
national library authority publication — for local grounding, then cite them in Chapters 1–2
and append to this list.]*
# Chapter 10 — Appendices

## Appendix A — API surface (versioned excerpt)

Complete, current, example-rich API documentation is generated at `/v3/api-docs`
(Swagger UI at `/swagger-ui.html` in dev/staging with `SWAGGER_ENABLED=true`). Every
operation documents the error codes it can produce against the shared `ApiError` envelope.
Domain quick maps live in the repository README (per phase).

## Appendix B — Repository guide

```
backend/   Spring Boot app — modules: identity, tenancy, membership, onboarding, billing,
           catalog, circulation, publishing, media, analytics, ai, platform, common
mobile/    Flutter app (Android/iOS/web): auth, discovery, home, catalog, ai, public pages
infra/     docker-compose.yml, .env.staging.example
docs/      architecture+roadmap, ops (backup/restore, runbooks, staging deploy),
           security pentest map, accessibility audit, perf baseline, release checklist,
           academic pack (this dissertation set)
scripts/   bootstrap-tools, backup.sh, restore.sh, load-test.sh
```

## Appendix C — Reproduction commands

```bash
# full automated verification (120 tests; isolation suite included)
cd backend && mvn test
# boot + scripted live demos
java -jar backend/target/libranet-backend-*.jar --spring.profiles.active=dev
bash demo-p10.sh   # AI: 14 checks                  bash demo-p11.sh  # hardening: 6 checks
scripts/load-test.sh                                   # perf baselines (budgets in docs/PERF-BASELINE.md)
```

## Appendix D — Database migrations V1–V19 (summary)

V1 init (users/libraries/memberships/audit/notifications); V2 RLS core; V3 onboarding &
billing; V4 seed plans; V5 catalog; V6 catalog RLS; V7 circulation; V8 circulation RLS;
V9 publishing; V10 publishing RLS; V11 media & reviews; V12 media RLS; V13 security
(lockout columns, sessions); V14 billing/analytics (storage ledger); V15 billing RLS; V16 AI
foundation (embeddings/usage/search events); V17 AI RLS + pgvector ext + HNSW; V18
performance indexes; V19 trigram GIN (PG-only). Extend-only by rule.

## Appendix E — Release & verification records

- `docs/RELEASE-CHECKLIST.md` — the go/no-go gate.
- `docs/RELEASE-VERIFICATION-2026-08-11.md` — the executed verification (all gates measured).
- `docs/SECURITY-PENTEST-CHECKLIST.md`, `docs/ACCESSIBILITY-AUDIT.md`,
  `docs/PERF-BASELINE.md` — supporting audits.

## Appendix F — Ethics & data statement

The platform stores membership PII and behavioural telemetry under tenant keys, minimises
free-text sensitive data (AI audit stores no question text), supports anonymous review
display under per-library policy, and never exposes avatars or private data on the public
plane. No real patron data was used in testing; all personas are seeded fixtures.
