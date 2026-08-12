# Performance baseline (Phase 11)

Measured 2026-08-11 with `scripts/load-test.sh` — 40 sequential client-side requests per
endpoint against the dev app (H2 in-memory; on Postgres numbers shift, shape shouldn't).
Re-run after every perf-sensitive change; compare against the budgets.

## Measured

| Endpoint | min | avg | p95 | max |
| --- | --- | --- | --- | --- |
| `GET /tenant/catalog/books?q=history&size=20` (auth, tenant search) | 12.4 ms | 20.5 ms | 29.7 ms | 94.2 ms |
| `GET /libraries?search=accra` (anonymous discovery) | 5.5 ms | 7.7 ms | 10.7 ms | 16.2 ms |
| `GET /public/libraries/{slug}/books` (public book page) | 7.3 ms | 11.4 ms | 17.1 ms | 22.2 ms |
| `GET /tenant/ai/search?q=history after 2010` (semantic, metered) | 18.2 ms | 32.9 ms | 49.8 ms | 99.6 ms |

## Budgets (p95, sequential single client)

| Endpoint | budget |
| --- | --- |
| tenant catalog search | ≤ 150 ms |
| anonymous discovery | ≤ 80 ms |
| public book page | ≤ 80 ms |
| AI semantic search | ≤ 400 ms |

## What Phase 11 changed for perf

- **V18 indexes** (portable): `books(tenant_id,published,title)` — the patron/public browse;
  `libraries(status,name)` — directory; `search_events(tenant_id,result_count,created_at)` —
  zero-hit demand aggregation; `audit_logs(action,created_at)` — cross-tenant audit search;
  `notifications(user_id,read_at)` — the per-screen unread poll. Verified by test
  (`HardeningTest.phase11IndexesExist`).
- **V19 trigram GIN indexes** (PG-only, Java migration): `lower(title/author/subjects)` on
  books, `lower(name)` on libraries — makes the `%like%` member search index-assisted on
  production Postgres. No-op on H2.
- **Edge caching**: anonymous reads advertise `Cache-Control: public, max-age=60` (discovery,
  public book pages, 30s on public reviews, 30d on media bytes). Invitation previews are
  explicitly `no-store`; private tenant endpoints never advertise public caching (test).
- **N+1 audit**: catalog search already batches availability (`countByBookGrouped`) and
  category names; dashboards are aggregate-count queries; AI paths do single bulk reads.
  No per-row query loop found in the hot read paths — nothing to fix.

## Not a stress test

Sequential requests bound client+server round-trip. For concurrency numbers run the same
endpoints under a real tool (e.g. `wrk -c 20 -d 30s`) on staging before launch — budget gate:
no error rate at 20 concurrent readers, p95 within 2× the budgets above.
