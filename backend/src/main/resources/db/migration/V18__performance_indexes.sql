-- V18 — performance index sweep (Phase 11). Portable (PG 16 + H2-PG-mode), all idempotent.
-- Gaps found by auditing repository query shapes against existing constraints/indexes.
-- Baselines live in docs/PERF-BASELINE.md.

-- Patron + public catalog browsing always filters published=true and scans/sorts by title.
-- The old (tenant_id, title) btree could not serve the published predicate alongside the sort.
CREATE INDEX IF NOT EXISTS idx_books_tenant_published_title ON books(tenant_id, published, title);

-- Anonymous directory browse: ACTIVE + directory-visible libraries sorted by name.
CREATE INDEX IF NOT EXISTS idx_libraries_status_name ON libraries(status, name);

-- Demand insights aggregates zero-hit searches:
--   WHERE tenant_id = ? AND result_count = 0 AND created_at >= ? GROUP BY query
-- The old (tenant_id, created_at) index could not use the result_count prefix.
CREATE INDEX IF NOT EXISTS idx_search_events_zero_hit ON search_events(tenant_id, result_count, created_at);

-- Platform audit viewer may search across ALL tenants by action (tenant filter nullable).
CREATE INDEX IF NOT EXISTS idx_audit_action_created ON audit_logs(action, created_at);

-- Per-user unread-badge poll on the notification feed (rendered on every app screen).
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications(user_id, read_at);
