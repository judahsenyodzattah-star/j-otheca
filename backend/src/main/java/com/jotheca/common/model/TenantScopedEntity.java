package com.jotheca.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base for every tenant-owned entity (isolation layer 5 of 9).
 * The filter is ARMED per request by TenantResolutionFilter when a tenant is resolved,
 * so JPA queries automatically append {@code tenant_id = :tenantId}. Defense-in-depth:
 * service guards and Postgres RLS also apply — no single layer is trusted alone.
 */
@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
