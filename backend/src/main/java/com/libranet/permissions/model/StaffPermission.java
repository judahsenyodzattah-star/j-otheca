package com.libranet.permissions.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One override row: {@code granted=true} adds beyond the role template; false revokes. */
@Entity
@Table(name = "staff_permissions")
public class StaffPermission extends TenantScopedEntity {

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(nullable = false, length = 30)
    private String perm;

    @Column(nullable = false)
    private boolean granted;

    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getPerm() { return perm; }
    public void setPerm(String perm) { this.perm = perm; }
    public boolean isGranted() { return granted; }
    public void setGranted(boolean granted) { this.granted = granted; }
}
