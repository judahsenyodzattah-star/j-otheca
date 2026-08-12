-- V14 — Quotas, billing lifecycle, patron rails, analytics (Phase 9). Portable SQL (PG 16 + H2-PG-mode).

-- Purchased extra storage per library, stacked on the plan cap at read time.
ALTER TABLE library_subscriptions ADD COLUMN storage_addon_gb INT NOT NULL DEFAULT 0;

-- Patron→Library rails reuse the payments ledger; these columns link a row to its tenant/purpose.
ALTER TABLE payments ADD COLUMN tenant_id VARCHAR(36) NULL;
ALTER TABLE payments ADD COLUMN fine_id  VARCHAR(36) NULL;
ALTER TABLE payments DROP CONSTRAINT chk_payment_purpose;
ALTER TABLE payments ADD CONSTRAINT chk_payment_purpose
    CHECK (purpose IN ('SUBSCRIPTION','STORAGE_ADDON','FINE'));
CREATE INDEX idx_payments_tenant ON payments(tenant_id, status);

-- Append-only storage ledger (spec: usage is data, not convention). Usage = SUM(delta_bytes).
-- Positive on upload, negative on retire/replace/delete. Rows are never updated or removed.
CREATE TABLE storage_usage (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36) NOT NULL REFERENCES libraries(id),
    delta_bytes     BIGINT      NOT NULL,
    reason          VARCHAR(20) NOT NULL,          -- MEDIA_UPLOAD | MEDIA_RETIRE | MEDIA_DELETE
    media_object_id VARCHAR(36),
    note            VARCHAR(300),
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT chk_storage_reason CHECK (reason IN ('MEDIA_UPLOAD','MEDIA_RETIRE','MEDIA_DELETE'))
);
CREATE INDEX idx_storage_usage_tenant ON storage_usage(tenant_id, created_at);

-- Backfill the ledger from pre-P9 media so existing installs keep an accurate quota baseline.
-- One MEDIA_UPLOAD row per ACTIVE media object (id reuses the media id; future ledger rows get
-- fresh app UUIDs, so no collision). PG + H2 portable.
INSERT INTO storage_usage (id, tenant_id, delta_bytes, reason, media_object_id, note, created_at, updated_at)
SELECT m.id, m.tenant_id, m.size_bytes, 'MEDIA_UPLOAD', m.id, 'backfill from media_objects',
       m.created_at, m.created_at
FROM media_objects m
WHERE m.status = 'ACTIVE';

-- Plan codes are DATA, not schema: new plans (e.g. annual tiers, regional pilots) must not need a
-- migration. The app validates codes when assigning; drop the closed set.
ALTER TABLE subscription_plans DROP CONSTRAINT chk_plan_code;
