-- V7 — Circulation (Phase 5): loan policies, loans, reservations, fines, in-app notifications.
-- Portable SQL (PostgreSQL 16 + H2-PG-mode). All tables tenant-owned (isolation layers apply; RLS via V8).

CREATE TABLE loan_policies (
    id                     VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id              VARCHAR(36) NOT NULL REFERENCES libraries(id),
    loan_days              INT         NOT NULL DEFAULT 14,
    renew_days             INT         NOT NULL DEFAULT 14,
    max_active_loans       INT         NOT NULL DEFAULT 5,
    max_renewals           INT         NOT NULL DEFAULT 2,
    max_reservations       INT         NOT NULL DEFAULT 3,
    pickup_deadline_hours  INT         NOT NULL DEFAULT 48,
    grace_days             INT         NOT NULL DEFAULT 0,   -- overdue forgiven within this window
    fine_per_day_minor     BIGINT      NOT NULL DEFAULT 200, -- GH₵2.00/day
    replacement_fee_minor  BIGINT      NOT NULL DEFAULT 50000,
    currency               VARCHAR(3)  NOT NULL DEFAULT 'GHS',
    created_at             TIMESTAMP   NOT NULL,
    updated_at             TIMESTAMP   NOT NULL,
    created_by             VARCHAR(255),
    updated_by             VARCHAR(255),
    CONSTRAINT uq_loan_policy_tenant UNIQUE (tenant_id)
);

CREATE TABLE loans (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id        VARCHAR(36) NOT NULL REFERENCES books(id),
    copy_id        VARCHAR(36) NOT NULL REFERENCES book_copies(id),
    membership_id  VARCHAR(36) NOT NULL,               -- memberships.id (the borrower identity)
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    issued_by_id   VARCHAR(36) NOT NULL,               -- staff user id who issued at the desk
    issued_at      TIMESTAMP   NOT NULL,
    due_at         TIMESTAMP   NOT NULL,
    renewed_count  INT         NOT NULL DEFAULT 0,
    returned_at    TIMESTAMP,
    returned_by_id VARCHAR(36),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | RETURNED (overdue is derived: ACTIVE & due<now)
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    CONSTRAINT chk_loan_status CHECK (status IN ('ACTIVE','RETURNED'))
);
-- open-loan-per-copy invariant is enforced in CirculationService (portable partial index not possible on H2)
CREATE INDEX idx_loans_open_copy ON loans(tenant_id, copy_id, status);
CREATE INDEX idx_loans_tenant_member ON loans(tenant_id, membership_id);
CREATE INDEX idx_loans_tenant_book  ON loans(tenant_id, book_id);
CREATE INDEX idx_loans_due ON loans(tenant_id, status, due_at);

CREATE TABLE reservations (
    id                 VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id          VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id            VARCHAR(36) NOT NULL REFERENCES books(id),
    membership_id      VARCHAR(36) NOT NULL,
    user_id            VARCHAR(36) NOT NULL REFERENCES users(id),
    copy_id            VARCHAR(36) NULL REFERENCES book_copies(id), -- set when a copy is assigned
    status             VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    pickup_deadline_at TIMESTAMP,
    ready_at           TIMESTAMP,
    collected_at       TIMESTAMP,
    loan_id            VARCHAR(36) NULL,                           -- loan created on collection
    cancelled_reason   VARCHAR(200),
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP   NOT NULL,
    created_by         VARCHAR(255),
    updated_by         VARCHAR(255),
    CONSTRAINT chk_reservation_status CHECK (status IN
        ('RESERVED','WAITING_FOR_PICKUP','READY_FOR_PICKUP','COLLECTED','CANCELLED','EXPIRED'))
);
CREATE INDEX idx_res_tenant_book ON reservations(tenant_id, book_id, status);
CREATE INDEX idx_res_tenant_member ON reservations(tenant_id, membership_id);
CREATE INDEX idx_res_deadline ON reservations(tenant_id, status, pickup_deadline_at);

CREATE TABLE fines (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(36) NOT NULL REFERENCES libraries(id),
    loan_id        VARCHAR(36) NULL,                    -- late/lost/damaged fines reference the loan
    membership_id  VARCHAR(36) NOT NULL,
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    type           VARCHAR(20) NOT NULL,                -- LATE | LOST | DAMAGED | REPLACEMENT | MANUAL
    amount_minor   BIGINT      NOT NULL,
    currency       VARCHAR(3)  NOT NULL DEFAULT 'GHS',
    status         VARCHAR(10) NOT NULL DEFAULT 'OPEN', -- OPEN | PAID | WAIVED
    note           VARCHAR(500),
    settled_at     TIMESTAMP,
    settled_by_id  VARCHAR(36),
    settlement     VARCHAR(200),                        -- e.g. "cash at desk"; patron rails are Phase 9
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    CONSTRAINT chk_fine_type CHECK (type IN ('LATE','LOST','DAMAGED','REPLACEMENT','MANUAL')),
    CONSTRAINT chk_fine_status CHECK (status IN ('OPEN','PAID','WAIVED'))
);
CREATE INDEX idx_fines_tenant_member ON fines(tenant_id, membership_id, status);

CREATE TABLE notifications (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    user_id    VARCHAR(36)  NOT NULL REFERENCES users(id),
    type       VARCHAR(40)  NOT NULL,                   -- RESERVATION_READY, LOAN_ISSUED, FINE_ASSESSED, ...
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(1000),
    read_at    TIMESTAMP,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);
CREATE INDEX idx_notify_user ON notifications(tenant_id, user_id, read_at);
