-- V5 — Catalog & discovery (Phase 4).
-- Portable SQL (PostgreSQL 16 + H2-PG-mode). Every table is tenant-owned (tenant_id -> libraries.id)
-- and joins the multi-layer isolation model (Hibernate tenantFilter + service TenantGuard + RLS via V6 java migration).

CREATE TABLE categories (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    parent_id       VARCHAR(36)  NULL REFERENCES categories(id),
    name            VARCHAR(200) NOT NULL,
    kind            VARCHAR(40),                         -- shelving area label, e.g. "Computer Science"
    description     VARCHAR(1000),
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT uq_categories_name UNIQUE (tenant_id, parent_id, name)
);
CREATE INDEX idx_categories_tenant ON categories(tenant_id);
-- NOTE: H2 treats NULL parent_id as distinct in the unique key (same as Postgres) so
-- two root categories with the same name still conflict — that is the intended behaviour.

CREATE TABLE branches (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    name            VARCHAR(200) NOT NULL,
    code            VARCHAR(40),                         -- short branch prefix for barcodes, e.g. "AC"
    address         VARCHAR(300),
    phone           VARCHAR(40),
    email           VARCHAR(255),
    is_main         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT uq_branches_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_branches_tenant ON branches(tenant_id);

CREATE TABLE shelf_locations (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    branch_id       VARCHAR(36)  NOT NULL REFERENCES branches(id),
    building        VARCHAR(60)  NOT NULL DEFAULT 'Main',
    floor           VARCHAR(60)  NOT NULL,
    section         VARCHAR(100) NOT NULL,
    shelf           VARCHAR(60)  NOT NULL,
    row_label       VARCHAR(60),                         -- "row" is a reserved word in several engines
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255)
);
CREATE INDEX idx_shelf_tenant ON shelf_locations(tenant_id);
CREATE INDEX idx_shelf_branch ON shelf_locations(branch_id);
CREATE UNIQUE INDEX uq_shelf_location ON shelf_locations(tenant_id, branch_id, building, floor, section, shelf, row_label);

CREATE TABLE books (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    slug            VARCHAR(160) NOT NULL,               -- public page key (Phase 6), unique per tenant
    title           VARCHAR(500) NOT NULL,
    subtitle        VARCHAR(500),
    author          VARCHAR(300) NOT NULL,
    co_authors      VARCHAR(500),
    isbn            VARCHAR(20),                         -- normalized (digits/X, no hyphens); unique per tenant when present
    publisher       VARCHAR(200),
    published_date  VARCHAR(10),                         -- ISO yyyy or yyyy-MM-dd; free-form years are common
    edition         VARCHAR(60),
    language        VARCHAR(60),
    page_count      INT,
    description     TEXT,
    subjects        VARCHAR(500),                        -- comma-separated tags/keywords
    category_id     VARCHAR(36)  NULL REFERENCES categories(id),
    cover_ref       VARCHAR(500),
    rating_avg      DOUBLE PRECISION NOT NULL DEFAULT 0,     -- aggregates written by Phase 7
    rating_count    INT          NOT NULL DEFAULT 0,
    published       BOOLEAN      NOT NULL DEFAULT TRUE,  -- hidden from patrons when FALSE
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT uq_books_slug UNIQUE (tenant_id, slug)
);
-- NULL ISBNs are distinct (PG & H2) → uniqueness enforced only when an ISBN exists.
CREATE UNIQUE INDEX uq_books_isbn ON books(tenant_id, isbn);
CREATE INDEX idx_books_tenant_title  ON books(tenant_id, title);
CREATE INDEX idx_books_tenant_author ON books(tenant_id, author);
CREATE INDEX idx_books_tenant_cat    ON books(tenant_id, category_id);

CREATE TABLE book_copies (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    book_id          VARCHAR(36)  NOT NULL REFERENCES books(id),
    barcode          VARCHAR(60)  NOT NULL,              -- e.g. AC-000001; unique per tenant
    accession_number VARCHAR(60),                        -- classic accession register number; unique per tenant when present
    status           VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    branch_id        VARCHAR(36)  NULL REFERENCES branches(id),
    shelf_location_id VARCHAR(36) NULL REFERENCES shelf_locations(id),
    notes            VARCHAR(1000),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    CONSTRAINT chk_copy_status CHECK (status IN
        ('AVAILABLE','BORROWED','RESERVED','LOST','DAMAGED','MAINTENANCE','REMOVED'))
);
CREATE UNIQUE INDEX uq_copies_barcode ON book_copies(tenant_id, barcode);
CREATE UNIQUE INDEX uq_copies_accession ON book_copies(tenant_id, accession_number);
CREATE INDEX idx_copies_tenant_book ON book_copies(tenant_id, book_id);
CREATE INDEX idx_copies_status ON book_copies(tenant_id, status);
