-- Phase 12 · Audit P2: tenant-scoped support issues + threaded messages (audit spec §17–§23).
-- Categories (§22) are exactly six: ACCOUNT_ACCESS | MEMBERSHIP | LOANS | PAYMENTS | TECHNICAL | OTHER.
-- Issues are numbered per tenant (LN-000123) so patrons and staff can reference them in speech.

CREATE TABLE issues (
    id                     VARCHAR(36)   NOT NULL PRIMARY KEY,
    tenant_id              VARCHAR(36)   NOT NULL REFERENCES libraries(id),
    issue_number           INTEGER       NOT NULL,
    reporter_membership_id VARCHAR(36)   NOT NULL,
    reporter_user_id       VARCHAR(36)   NOT NULL REFERENCES users(id),
    category               VARCHAR(20)   NOT NULL,
    subject                VARCHAR(200)  NOT NULL,
    description            VARCHAR(10000) NOT NULL,        -- 10k cap (spec §21), enforced in service too
    status                 VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    priority               VARCHAR(10)   NOT NULL DEFAULT 'NORMAL',
    assignee_membership_id VARCHAR(36),
    source                 VARCHAR(8)    NOT NULL DEFAULT 'APP', -- APP | AI (AI = assistant-drafted, user-confirmed)
    client_key             VARCHAR(64),                        -- idempotent create: retries/double-taps replay
    resolved_at            TIMESTAMP,
    created_at             TIMESTAMP     NOT NULL,
    updated_at             TIMESTAMP     NOT NULL,
    created_by             VARCHAR(255),
    updated_by             VARCHAR(255),
    CONSTRAINT chk_issue_category CHECK (category IN
        ('ACCOUNT_ACCESS','MEMBERSHIP','LOANS','PAYMENTS','TECHNICAL','OTHER')),
    CONSTRAINT chk_issue_status CHECK (status IN
        ('OPEN','NEEDS_INFO','IN_PROGRESS','RESOLVED','ESCALATED')),
    CONSTRAINT chk_issue_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT chk_issue_source CHECK (source IN ('APP','AI')),
    CONSTRAINT uq_issue_number UNIQUE (tenant_id, issue_number),
    CONSTRAINT uq_issue_client_key UNIQUE (tenant_id, reporter_user_id, client_key)
);
CREATE INDEX idx_issues_inbox ON issues(tenant_id, status, category, priority);
CREATE INDEX idx_issues_reporter ON issues(tenant_id, reporter_user_id, created_at);
CREATE INDEX idx_issues_assignee ON issues(tenant_id, assignee_membership_id, status);

CREATE TABLE issue_messages (
    id                   VARCHAR(36)    NOT NULL PRIMARY KEY,
    tenant_id            VARCHAR(36)    NOT NULL REFERENCES libraries(id),
    issue_id             VARCHAR(36)    NOT NULL REFERENCES issues(id),
    author_membership_id VARCHAR(36),                          -- NULL = system/AI note
    author_user_id       VARCHAR(36),
    body                 VARCHAR(10000) NOT NULL,
    internal             BOOLEAN        NOT NULL DEFAULT FALSE, -- staff-only note, hidden from reporter
    created_at           TIMESTAMP      NOT NULL,
    updated_at           TIMESTAMP      NOT NULL,
    created_by           VARCHAR(255),
    updated_by           VARCHAR(255)
);
CREATE INDEX idx_issue_messages_thread ON issue_messages(tenant_id, issue_id, created_at);
