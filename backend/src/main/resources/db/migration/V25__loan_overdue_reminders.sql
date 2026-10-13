-- V25 (audit 2026-10-10): idempotent lazy overdue-reminder sweep. Records that a reminder was sent for a
-- loan so the sweep (run on the member's own loan read) never spams. Schema-only additive change — no data
-- rewrite, no RLS impact. Matches the loans table's existing plain TIMESTAMP columns (H2 + Postgres).
ALTER TABLE loans ADD COLUMN reminder_sent_at TIMESTAMP NULL;
