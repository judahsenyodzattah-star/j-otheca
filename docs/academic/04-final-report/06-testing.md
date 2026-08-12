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
