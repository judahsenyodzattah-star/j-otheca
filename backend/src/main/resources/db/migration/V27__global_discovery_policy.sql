-- V27 - Global discovery policy foundation.
-- Conservative rollout: existing libraries and books are excluded from the
-- future global discovery surface. Existing per-library public catalog routes
-- intentionally do not read these columns in this phase.

ALTER TABLE libraries
    ADD COLUMN catalog_visibility VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY';

ALTER TABLE libraries
    ADD COLUMN holdings_visibility VARCHAR(20) NOT NULL DEFAULT 'PRESENCE';

ALTER TABLE books
    ADD COLUMN global_discoverable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE libraries
    ADD CONSTRAINT chk_libraries_catalog_visibility
        CHECK (catalog_visibility IN ('PUBLIC', 'PARTIALLY_PUBLIC', 'MEMBERS_ONLY'));

ALTER TABLE libraries
    ADD CONSTRAINT chk_libraries_holdings_visibility
        CHECK (holdings_visibility IN ('PRESENCE', 'AVAILABILITY', 'COPY_COUNT', 'BRANCH'));

CREATE INDEX idx_libraries_global_discovery
    ON libraries(status, visibility, catalog_visibility);

CREATE INDEX idx_books_global_discovery
    ON books(tenant_id, published, global_discoverable);
