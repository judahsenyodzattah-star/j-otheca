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
