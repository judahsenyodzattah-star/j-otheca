-- V1 — Identity, tenancy, membership core.
-- Portable SQL (runs on PostgreSQL 16 and H2-PG-mode for dev/tests).
-- All IDs are app-generated UUID strings (varchar 36) so no DB-specific sequence types are needed.

CREATE TABLE users (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    full_name           VARCHAR(200) NOT NULL,
    phone               VARCHAR(40),
    avatar_ref          VARCHAR(500),
    global_role         VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified_at   TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (global_role IN ('USER','SUPER_ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);

CREATE TABLE auth_sessions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(64)  NOT NULL,          -- sha256 of the opaque refresh token; never the token itself
    family          VARCHAR(36)  NOT NULL,          -- rotation lineage; reuse of a revoked token revokes the family
    device_info     VARCHAR(300),
    ip              VARCHAR(60),
    expires_at      TIMESTAMP    NOT NULL,
    revoked_at      TIMESTAMP,
    replaced_by     VARCHAR(36),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255)
);
CREATE INDEX idx_auth_sessions_user   ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_hash   ON auth_sessions(token_hash);
CREATE INDEX idx_auth_sessions_family ON auth_sessions(family);

CREATE TABLE verification_tokens (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL REFERENCES users(id),
    type        VARCHAR(30)  NOT NULL,              -- EMAIL_VERIFY | PASSWORD_RESET
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    CONSTRAINT chk_verify_type CHECK (type IN ('EMAIL_VERIFY','PASSWORD_RESET'))
);
CREATE INDEX idx_verify_user ON verification_tokens(user_id);
CREATE INDEX idx_verify_hash ON verification_tokens(token_hash);

CREATE TABLE audit_logs (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(36),                        -- NULL for platform-plane events
    actor_id    VARCHAR(36),
    actor_email VARCHAR(255),
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(60),
    entity_id   VARCHAR(64),
    metadata    TEXT,
    ip          VARCHAR(60),
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_audit_tenant  ON audit_logs(tenant_id);
CREATE INDEX idx_audit_actor   ON audit_logs(actor_id);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

CREATE TABLE libraries (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,   -- tenant root; no tenant_id on this table
    uid             VARCHAR(12)  NOT NULL,               -- internal permanent UID, e.g. LIB-H92KD8
    slug            VARCHAR(120) NOT NULL,               -- public URL segment
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    join_policy     VARCHAR(30)  NOT NULL DEFAULT 'PUBLIC',
    visibility      BOOLEAN      NOT NULL DEFAULT TRUE,
    description     TEXT,
    country         VARCHAR(100),
    city            VARCHAR(100),
    address         VARCHAR(300),
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    opening_hours   VARCHAR(300),
    services        VARCHAR(500),
    allowed_domain  VARCHAR(200),                        -- for DOMAIN_VERIFIED joins
    logo_ref        VARCHAR(500),
    banner_ref      VARCHAR(500),
    member_count    INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT uq_libraries_uid  UNIQUE (uid),
    CONSTRAINT uq_libraries_slug UNIQUE (slug),
    CONSTRAINT chk_libraries_type   CHECK (type IN ('UNIVERSITY','SCHOOL','PUBLIC','PRIVATE','CORPORATE','RESEARCH','PERSONAL')),
    CONSTRAINT chk_libraries_status CHECK (status IN ('PENDING_APPROVAL','ACTIVE','SUSPENDED')),
    CONSTRAINT chk_libraries_policy CHECK (join_policy IN ('PUBLIC','APPROVAL_REQUIRED','STUDENT_ONLY','STAFF_ONLY','INVITATION_ONLY','DOMAIN_VERIFIED','CUSTOM'))
);
CREATE INDEX idx_libraries_city    ON libraries(city);
CREATE INDEX idx_libraries_country ON libraries(country);
CREATE INDEX idx_libraries_status  ON libraries(status);

CREATE TABLE memberships (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id           VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    user_id             VARCHAR(36)  NOT NULL REFERENCES users(id),
    role                VARCHAR(20)  NOT NULL DEFAULT 'PATRON',
    membership_type     VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC_MEMBER',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    membership_number   VARCHAR(20),                   -- assigned on approval
    verification_payload TEXT,                         -- JSON: studentId, programme, department, ...
    joined_at           TIMESTAMP,
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    CONSTRAINT uq_membership UNIQUE (tenant_id, user_id),
    CONSTRAINT uq_membership_number UNIQUE (tenant_id, membership_number),
    CONSTRAINT chk_membership_role   CHECK (role IN ('LIBRARY_ADMIN','LIBRARIAN','PATRON')),
    CONSTRAINT chk_membership_type   CHECK (membership_type IN ('STUDENT','STAFF','FACULTY','RESEARCHER','ALUMNI','PUBLIC_MEMBER','GUEST','AUTHOR')),
    CONSTRAINT chk_membership_status CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED','EXPIRED'))
);
CREATE INDEX idx_memberships_user ON memberships(user_id);

CREATE TABLE invitations (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    slug            VARCHAR(120) NOT NULL,               -- /join/{slug}
    code            VARCHAR(20)  NOT NULL,               -- short manual-entry code
    allowed_role    VARCHAR(20)  NOT NULL DEFAULT 'PATRON',
    membership_type VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC_MEMBER',
    auto_approve    BOOLEAN      NOT NULL DEFAULT TRUE,
    max_uses        INT,                                 -- NULL = unlimited
    used_count      INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMP,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT uq_invitation_slug UNIQUE (slug),
    CONSTRAINT uq_invitation_code UNIQUE (code)
);
CREATE INDEX idx_invitations_tenant ON invitations(tenant_id);
