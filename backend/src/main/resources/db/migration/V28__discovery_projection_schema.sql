-- V28 - Internal global-discovery projection schema foundation.
-- No roles, grants, views, endpoints, or synchronization are introduced here.
-- The schema is intentionally empty of data until a future opt-in workflow exists.

CREATE SCHEMA IF NOT EXISTS discovery;

CREATE TABLE discovery.editions (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    edition_key           VARCHAR(220) NOT NULL,
    normalized_isbn13     VARCHAR(13),
    title                 VARCHAR(500) NOT NULL,
    subtitle              VARCHAR(500),
    author_display        VARCHAR(500) NOT NULL,
    publisher             VARCHAR(200),
    published_date        VARCHAR(10),
    edition_label         VARCHAR(60),
    language              VARCHAR(60),
    page_count            INT,
    cover_ref             VARCHAR(500),
    subjects              VARCHAR(500),
    metadata_fingerprint  VARCHAR(64)  NOT NULL,
    metadata_state        VARCHAR(20)  NOT NULL DEFAULT 'CONSISTENT',
    active                BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at            TIMESTAMP,
    projection_version    BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT uq_discovery_editions_key UNIQUE (edition_key),
    CONSTRAINT chk_discovery_editions_isbn
        CHECK (normalized_isbn13 IS NULL OR CHAR_LENGTH(normalized_isbn13) = 13),
    CONSTRAINT chk_discovery_editions_metadata_state
        CHECK (metadata_state IN ('CONSISTENT', 'CONFLICTED')),
    CONSTRAINT chk_discovery_editions_version CHECK (projection_version >= 0),
    CONSTRAINT chk_discovery_editions_active
        CHECK (active = FALSE OR revoked_at IS NULL)
);

CREATE TABLE discovery.holdings (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    edition_id            VARCHAR(36)  NOT NULL REFERENCES discovery.editions(id),
    library_public_id     VARCHAR(36)  NOT NULL REFERENCES public.libraries(id),
    library_slug          VARCHAR(120) NOT NULL,
    library_name          VARCHAR(200) NOT NULL,
    library_logo_ref      VARCHAR(500),
    country               VARCHAR(100),
    city                  VARCHAR(100),
    catalog_visibility    VARCHAR(20)  NOT NULL,
    holdings_visibility   VARCHAR(20)  NOT NULL,
    availability_status   VARCHAR(20),
    copy_count            INT,
    branch_summary        TEXT,
    active                BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at            TIMESTAMP,
    projection_version    BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT uq_discovery_holding_edition_library UNIQUE (edition_id, library_public_id),
    CONSTRAINT uq_discovery_holding_id_edition UNIQUE (id, edition_id),
    CONSTRAINT chk_discovery_holdings_catalog_visibility
        CHECK (catalog_visibility IN ('PUBLIC', 'PARTIALLY_PUBLIC')),
    CONSTRAINT chk_discovery_holdings_visibility
        CHECK (holdings_visibility IN ('PRESENCE', 'AVAILABILITY', 'COPY_COUNT', 'BRANCH')),
    CONSTRAINT chk_discovery_holdings_availability
        CHECK (availability_status IS NULL OR availability_status IN
            ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN')),
    CONSTRAINT chk_discovery_holdings_copy_count CHECK (copy_count IS NULL OR copy_count >= 0),
    CONSTRAINT chk_discovery_holdings_version CHECK (projection_version >= 0),
    CONSTRAINT chk_discovery_holdings_active
        CHECK (active = FALSE OR revoked_at IS NULL)
);

CREATE TABLE discovery.holding_sources (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    holding_id            VARCHAR(36)  NOT NULL,
    edition_id            VARCHAR(36)  NOT NULL,
    source_library_id     VARCHAR(36)  NOT NULL REFERENCES public.libraries(id),
    source_book_id        VARCHAR(36)  NOT NULL REFERENCES public.books(id),
    source_version        BIGINT       NOT NULL DEFAULT 0,
    sync_state             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    conflict_reason       VARCHAR(500),
    last_seen_at          TIMESTAMP,
    revoked_at            TIMESTAMP,
    projection_version    BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT fk_discovery_source_holding_edition
        FOREIGN KEY (holding_id, edition_id)
        REFERENCES discovery.holdings(id, edition_id),
    CONSTRAINT fk_discovery_source_edition
        FOREIGN KEY (edition_id) REFERENCES discovery.editions(id),
    CONSTRAINT uq_discovery_source_identity UNIQUE (source_library_id, source_book_id),
    CONSTRAINT chk_discovery_source_state
        CHECK (sync_state IN ('PENDING', 'ACTIVE', 'CONFLICTED', 'REVOKED')),
    CONSTRAINT chk_discovery_source_versions
        CHECK (source_version >= 0 AND projection_version >= 0),
    CONSTRAINT chk_discovery_source_conflict
        CHECK ((sync_state = 'CONFLICTED' AND conflict_reason IS NOT NULL)
            OR (sync_state <> 'CONFLICTED'))
);

CREATE INDEX idx_discovery_editions_search
    ON discovery.editions(active, title, author_display);
CREATE INDEX idx_discovery_editions_isbn
    ON discovery.editions(normalized_isbn13);
CREATE INDEX idx_discovery_holdings_library
    ON discovery.holdings(library_public_id, active);
CREATE INDEX idx_discovery_holdings_edition
    ON discovery.holdings(edition_id, active);
CREATE INDEX idx_discovery_sources_holding
    ON discovery.holding_sources(holding_id, sync_state);
CREATE INDEX idx_discovery_sources_reconciliation
    ON discovery.holding_sources(source_library_id, sync_state, projection_version);
