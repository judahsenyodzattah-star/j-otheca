-- V3 — SaaS onboarding & platform billing (spec §11, §16, §31)
-- Plan limits are DATA, not code: every limit the platform enforces lives in subscription_plans.limits.

CREATE TABLE subscription_plans (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL,
    name                VARCHAR(60)  NOT NULL,
    description         VARCHAR(500),
    price_monthly_minor BIGINT       NOT NULL DEFAULT 0,   -- minor units (pesewas), no floats for money
    currency            VARCHAR(3)   NOT NULL DEFAULT 'GHS',
    limits              TEXT         NOT NULL,              -- JSON: storageGb/maxMembers/maxStaff/...
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order          INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    CONSTRAINT uq_plan_code UNIQUE (code),
    CONSTRAINT chk_plan_code CHECK (code IN ('STARTER','PROFESSIONAL','ENTERPRISE'))
);

CREATE TABLE library_onboarding_requests (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    applicant_user_id VARCHAR(36)  NOT NULL REFERENCES users(id),
    library_name      VARCHAR(200) NOT NULL,
    type              VARCHAR(30)  NOT NULL,
    country           VARCHAR(100),
    city              VARCHAR(100),
    address           VARCHAR(300),
    description       TEXT,
    plan_code         VARCHAR(20)  NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'SUBMITTED',
    rejection_reason  VARCHAR(500),
    library_id        VARCHAR(36)  REFERENCES libraries(id),   -- set once provisioned
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    created_by        VARCHAR(255),
    updated_by        VARCHAR(255),
    CONSTRAINT chk_onboarding_type   CHECK (type IN ('UNIVERSITY','SCHOOL','PUBLIC','PRIVATE','CORPORATE','RESEARCH','PERSONAL')),
    CONSTRAINT chk_onboarding_status CHECK (status IN ('SUBMITTED','PAYMENT_CONFIRMED','APPROVED','REJECTED'))
);
CREATE INDEX idx_onboarding_applicant ON library_onboarding_requests(applicant_user_id);
CREATE INDEX idx_onboarding_status    ON library_onboarding_requests(status);

CREATE TABLE payments (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    payer_user_id         VARCHAR(36)  NOT NULL REFERENCES users(id),
    onboarding_request_id VARCHAR(36)  REFERENCES library_onboarding_requests(id),
    amount_minor          BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    purpose               VARCHAR(30)  NOT NULL DEFAULT 'SUBSCRIPTION',
    provider              VARCHAR(20)  NOT NULL,              -- PAYSTACK | MOCK
    provider_reference    VARCHAR(80)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'INITIATED',
    paid_at               TIMESTAMP,
    metadata              TEXT,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT uq_payment_ref UNIQUE (provider, provider_reference),
    CONSTRAINT chk_payment_status CHECK (status IN ('INITIATED','PAID','FAILED','CANCELLED')),
    CONSTRAINT chk_payment_purpose CHECK (purpose IN ('SUBSCRIPTION','STORAGE_ADDON'))
);
CREATE INDEX idx_payments_payer ON payments(payer_user_id);

CREATE TABLE library_subscriptions (
    id                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id            VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    plan_id              VARCHAR(36)  NOT NULL REFERENCES subscription_plans(id),
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    current_period_start TIMESTAMP    NOT NULL,
    current_period_end   TIMESTAMP    NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    created_by           VARCHAR(255),
    updated_by           VARCHAR(255),
    CONSTRAINT uq_subscription_tenant UNIQUE (tenant_id),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE','PAST_DUE','CANCELLED','EXPIRED'))
);
