package com.jotheca.membership.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** Shareable join entry points for a library: link slug (also QR-able) + manual code (spec §14). */
@Entity
@Table(name = "invitations")
public class Invitation extends TenantScopedEntity {

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "allowed_role", nullable = false, length = 20)
    private LibraryRole allowedRole = LibraryRole.PATRON;   // librarians are appointed, never invited (spec §9)

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", nullable = false, length = 20)
    private MembershipType membershipType = MembershipType.PUBLIC_MEMBER;

    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove = true;

    @Column(name = "max_uses")
    private Integer maxUses;   // NULL = unlimited

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean active = true;

    public boolean usable(Instant now) {
        return active
                && (expiresAt == null || expiresAt.isAfter(now))
                && (maxUses == null || usedCount < maxUses);
    }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LibraryRole getAllowedRole() { return allowedRole; }
    public void setAllowedRole(LibraryRole allowedRole) { this.allowedRole = allowedRole; }
    public MembershipType getMembershipType() { return membershipType; }
    public void setMembershipType(MembershipType membershipType) { this.membershipType = membershipType; }
    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
