package com.jotheca.membership.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * THE central security object (spec §5): User → Membership → Library.
 * Every permission a user has inside a library flows from this row:
 * role, membership type, status, verification state, expiry.
 */
@Entity
@Table(name = "memberships")
public class Membership extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LibraryRole role = LibraryRole.PATRON;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", nullable = false, length = 20)
    private MembershipType membershipType = MembershipType.PUBLIC_MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status = MembershipStatus.PENDING;

    /** Assigned on approval; shown on the digital membership card (spec §25). */
    @Column(name = "membership_number", length = 20)
    private String membershipNumber;

    /** JSON: studentId, employeeId, faculty, programme, level, department, institutionalEmail, ... (spec §13). */
    @Column(name = "verification_payload", columnDefinition = "TEXT")
    private String verificationPayload;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public LibraryRole getRole() { return role; }
    public void setRole(LibraryRole role) { this.role = role; }
    public MembershipType getMembershipType() { return membershipType; }
    public void setMembershipType(MembershipType membershipType) { this.membershipType = membershipType; }
    public MembershipStatus getStatus() { return status; }
    public void setStatus(MembershipStatus status) { this.status = status; }
    public String getMembershipNumber() { return membershipNumber; }
    public void setMembershipNumber(String membershipNumber) { this.membershipNumber = membershipNumber; }
    public String getVerificationPayload() { return verificationPayload; }
    public void setVerificationPayload(String verificationPayload) { this.verificationPayload = verificationPayload; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
