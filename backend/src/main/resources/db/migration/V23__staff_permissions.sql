-- Phase 14 · Audit P4/E19: granular per-staff permission overrides. Roles stay the honest
-- template (librarian default pack, admin = everything); rows here grant extra or revoke
-- default permissions for ONE membership in ONE tenant. LIBRARY_ADMIN is never overridable
-- (self-lockout guard lives in the service).

CREATE TABLE staff_permissions (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    membership_id VARCHAR(36)  NOT NULL,
    perm          VARCHAR(30)  NOT NULL,
    granted       BOOLEAN      NOT NULL,                -- true = grant beyond defaults, false = revoke a default
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT uq_staff_perm UNIQUE (tenant_id, membership_id, perm),
    CONSTRAINT chk_staff_perm CHECK (perm IN
        ('CATALOG_MANAGE','CIRCULATION_OPS','FINES_MANAGE','FINES_WAIVE','MEMBERS_MANAGE',
         'POLICY_MANAGE','REVIEWS_MODERATE','ISSUES_HANDLE','REPORTS_VIEW','BILLING_MANAGE'))
);
CREATE INDEX idx_staff_perm_member ON staff_permissions(tenant_id, membership_id);
