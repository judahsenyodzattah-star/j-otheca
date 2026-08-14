-- Phase 13 · Audit P3: author as a CAPABILITY (spec rule: author coexists with patron — same
-- account, optional profile). Privacy-first: the legal identity on the User row is NEVER
-- auto-public; only fields the author explicitly publishes appear on the public author page.
-- Payout details are collected ONLY at seller activation (implementation rule #15).

CREATE TABLE author_profiles (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(36)  NOT NULL REFERENCES users(id),
    slug             VARCHAR(80)  NOT NULL,
    pen_name         VARCHAR(120) NOT NULL,                -- public name; legal name never leaks
    bio              VARCHAR(2000),
    photo_url        VARCHAR(500),
    country          VARCHAR(100),
    languages        VARCHAR(300),                          -- CSV: "English, Twi"
    genres           VARCHAR(300),                          -- CSV
    links            TEXT,                                  -- JSON [{label,url}]
    author_type      VARCHAR(24)  NOT NULL DEFAULT 'INDIE', -- INDIE | TRADITIONALLY_PUBLISHED | HYBRID | ORGANIZATION
    public_profile   BOOLEAN      NOT NULL DEFAULT FALSE,   -- privacy flag: public page exists only when TRUE
    public_email     VARCHAR(255),                          -- optional public contact (author's choice)
    selling_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    payout_momo_number  VARCHAR(32),                        -- collected at activation only
    payout_momo_network VARCHAR(32),                        -- MTN | TELECEL | AIRTELTIGO
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    CONSTRAINT uq_author_user UNIQUE (user_id),
    CONSTRAINT uq_author_slug UNIQUE (slug),
    CONSTRAINT chk_author_type CHECK (author_type IN
        ('INDIE','TRADITIONALLY_PUBLISHED','HYBRID','ORGANIZATION')),
    CONSTRAINT chk_author_network CHECK (payout_momo_network IS NULL OR payout_momo_network IN
        ('MTN','TELECEL','AIRTELTIGO'))
);

-- E18: book ↔ author-profile link (nullable; the legacy authorUserId stays for compatibility).
ALTER TABLE books ADD COLUMN author_profile_id VARCHAR(36);
CREATE INDEX idx_books_author_profile ON books(author_profile_id) ;
