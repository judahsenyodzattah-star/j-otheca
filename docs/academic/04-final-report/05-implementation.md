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
