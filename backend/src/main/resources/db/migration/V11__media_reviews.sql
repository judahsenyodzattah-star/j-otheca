-- V11 — Media, ratings & reviews (Phase 7). Portable SQL (PG 16 + H2-PG-mode).
-- Media objects are METADATA only; bytes live in object storage (MinIO/S3) under
-- tenant-prefixed keys. Deletion is a status flag — data is never destroyed (spec).

ALTER TABLE libraries ADD COLUMN allow_anonymous_reviews BOOLEAN NOT NULL DEFAULT FALSE;

-- An uploaded image (cover / logo / banner / avatar) with a thumbnail variant.
CREATE TABLE media_objects (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    kind          VARCHAR(20)  NOT NULL,          -- BOOK_COVER | LIBRARY_LOGO | LIBRARY_BANNER | USER_AVATAR
    object_key    VARCHAR(500) NOT NULL,          -- storage key, tenant-prefixed: t/{tenantId}/{uuid}.{ext}
    thumb_key     VARCHAR(500),                   -- thumbnail variant key
    content_type  VARCHAR(100) NOT NULL,          -- SNIFFED from magic bytes, never trusted from the client
    size_bytes    BIGINT       NOT NULL,          -- full + thumb bytes; basis of the storage quota hard-check
    width         INT,
    height        INT,
    original_name VARCHAR(255),
    status        VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DELETED (never destroyed)
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT chk_media_kind   CHECK (kind   IN ('BOOK_COVER','LIBRARY_LOGO','LIBRARY_BANNER','USER_AVATAR')),
    CONSTRAINT chk_media_status CHECK (status IN ('ACTIVE','DELETED'))
);
CREATE UNIQUE INDEX uq_media_object_key ON media_objects(object_key);
CREATE INDEX idx_media_tenant ON media_objects(tenant_id, status);

-- 1–5 stars; one rating per member per book; aggregates denormalized onto books.
CREATE TABLE book_ratings (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id       VARCHAR(36) NOT NULL REFERENCES books(id),
    membership_id VARCHAR(36) NOT NULL,
    user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    stars         INT         NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT uq_rating UNIQUE (tenant_id, book_id, membership_id),
    CONSTRAINT chk_rating_stars CHECK (stars BETWEEN 1 AND 5)
);
CREATE INDEX idx_rating_book ON book_ratings(tenant_id, book_id);

-- Long-form reviews. One non-deleted review per member per book. Moderated by staff.
-- REJECTED reviews keep a moderation note; DELETED is the soft-delete flag state.
CREATE TABLE book_reviews (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    book_id          VARCHAR(36)  NOT NULL REFERENCES books(id),
    membership_id    VARCHAR(36)  NOT NULL,
    user_id          VARCHAR(36)  NOT NULL REFERENCES users(id),
    title            VARCHAR(200),
    body             TEXT         NOT NULL,
    anonymous        BOOLEAN      NOT NULL DEFAULT FALSE,  -- only honored when the library policy allows it
    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED | DELETED
    moderation_note  VARCHAR(500),                          -- staff-visible reason, esp. on rejection
    moderated_by_id  VARCHAR(36),
    moderated_at     TIMESTAMP,
    helpful_count    INT          NOT NULL DEFAULT 0,       -- denormalized from review_helpful_votes
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    CONSTRAINT uq_review UNIQUE (tenant_id, book_id, membership_id),
    CONSTRAINT chk_review_status CHECK (status IN ('PENDING','APPROVED','REJECTED','DELETED'))
);
CREATE INDEX idx_review_book ON book_reviews(tenant_id, book_id, status);

-- Abuse reports against reviews; one report per member per review.
CREATE TABLE review_reports (
    id                     VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id              VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    review_id              VARCHAR(36)  NOT NULL REFERENCES book_reviews(id),
    reporter_membership_id VARCHAR(36)  NOT NULL,
    reporter_user_id       VARCHAR(36)  NOT NULL REFERENCES users(id),
    reason                 VARCHAR(500) NOT NULL,
    status                 VARCHAR(12)  NOT NULL DEFAULT 'OPEN',  -- OPEN | RESOLVED | DISMISSED
    resolved_by_id         VARCHAR(36),
    resolved_at            TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    created_by             VARCHAR(255),
    updated_by             VARCHAR(255),
    CONSTRAINT uq_report UNIQUE (tenant_id, review_id, reporter_membership_id),
    CONSTRAINT chk_report_status CHECK (status IN ('OPEN','RESOLVED','DISMISSED'))
);
CREATE INDEX idx_report_review ON review_reports(tenant_id, review_id, status);

-- "Was this review helpful?" — one vote per member per review.
CREATE TABLE review_helpful_votes (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(36) NOT NULL REFERENCES libraries(id),
    review_id     VARCHAR(36) NOT NULL REFERENCES book_reviews(id),
    membership_id VARCHAR(36) NOT NULL,
    user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT uq_helpful UNIQUE (tenant_id, review_id, membership_id)
);
CREATE INDEX idx_helpful_review ON review_helpful_votes(tenant_id, review_id);
