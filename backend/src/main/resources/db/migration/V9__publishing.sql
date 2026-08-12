-- V9 — Publishing (Phase 6): author attribution + physical-sale pricing on books,
-- reader interest tracking, purchase requests. Portable SQL (PG 16 + H2-PG-mode).

ALTER TABLE books ADD COLUMN author_user_id VARCHAR(36) NULL REFERENCES users(id);
ALTER TABLE books ADD COLUMN sale_price_minor BIGINT NULL;   -- NULL = not for sale; 0+ = physical price
ALTER TABLE books ADD COLUMN contact_note VARCHAR(300) NULL; -- e.g. "Signed copies at the front desk"

-- "I want this" — demand signal for librarians/authors; one mark per member per book.
CREATE TABLE book_interests (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id       VARCHAR(36) NOT NULL REFERENCES books(id),
    membership_id VARCHAR(36) NOT NULL,
    user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT uq_interest UNIQUE (tenant_id, book_id, membership_id)
);
CREATE INDEX idx_interest_book ON book_interests(tenant_id, book_id);
CREATE INDEX idx_interest_member ON book_interests(tenant_id, membership_id);

-- Physical-first sales: request at the desk (or via app), staff fulfills with a real copy.
CREATE TABLE purchase_requests (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id        VARCHAR(36) NOT NULL REFERENCES books(id),
    copy_id        VARCHAR(36) NULL REFERENCES book_copies(id), -- copy handed over on fulfillment
    membership_id  VARCHAR(36) NOT NULL,
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    status         VARCHAR(12) NOT NULL DEFAULT 'PENDING',      -- PENDING | FULFILLED | CANCELLED
    price_minor    BIGINT      NOT NULL,                        -- snapshot of the price at request time
    currency       VARCHAR(3)  NOT NULL DEFAULT 'GHS',
    note           VARCHAR(500),
    fulfilled_at   TIMESTAMP,
    fulfilled_by_id VARCHAR(36),
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    CONSTRAINT chk_purchase_status CHECK (status IN ('PENDING','FULFILLED','CANCELLED'))
);
CREATE INDEX idx_purchase_tenant_book ON purchase_requests(tenant_id, book_id, status);
CREATE INDEX idx_purchase_member ON purchase_requests(tenant_id, membership_id);
