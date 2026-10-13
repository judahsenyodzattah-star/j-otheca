package com.jotheca.billing.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** A library's current plan assignment (platform billing: Library → J’otheca, spec §31). */
@Entity
@Table(name = "library_subscriptions")
public class LibrarySubscription extends BaseEntity {

    public enum Status { ACTIVE, PAST_DUE, CANCELLED, EXPIRED }

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "plan_id", nullable = false, length = 36)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    /** Purchased extra storage in GB (Phase 9, STORAGE_ADDON payments) — stacked on the plan cap. */
    @Column(name = "storage_addon_gb", nullable = false)
    private int storageAddonGb;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }
    public int getStorageAddonGb() { return storageAddonGb; }
    public void setStorageAddonGb(int storageAddonGb) { this.storageAddonGb = storageAddonGb; }
}
