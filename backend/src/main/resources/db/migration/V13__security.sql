-- V13 — Advanced security & audit (Phase 8). Portable SQL (PG 16 + H2-PG-mode).
-- Real TOTP MFA, failed-login lockout, session "last used" tracking, audit-viewer indexes.

-- MFA (RFC 6238 TOTP). The secret is at rest only here; it is never written to the audit log.
ALTER TABLE users ADD COLUMN mfa_secret VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Account lockout after repeated failed logins (durable, not a cache — survives restarts).
ALTER TABLE users ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMP NULL;

-- Session/device view: when this session last refreshed.
ALTER TABLE auth_sessions ADD COLUMN last_used_at TIMESTAMP NULL;

-- Audit viewer (Phase 8) filters on these constantly. idx_audit_created/idx_audit_tenant
-- already exist from V1; only the composite is new.
CREATE INDEX IF NOT EXISTS idx_audit_tenant_action ON audit_logs(tenant_id, action, created_at);
