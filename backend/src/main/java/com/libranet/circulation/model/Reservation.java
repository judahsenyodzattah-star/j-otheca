package com.libranet.circulation.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A member's hold on a title. Spec §21 lifecycle:
 * RESERVED → WAITING_FOR_PICKUP (copy assigned, being pulled) → READY_FOR_PICKUP (on the hold shelf)
 * → COLLECTED (desk checkout creates the loan; the copy is then BORROWED on the loan).
 * Terminal side-exits: CANCELLED (member/staff), EXPIRED (pickup deadline missed).
 */
@Entity
@Table(name = "reservations")
public class Reservation extends TenantScopedEntity {

    public enum Status { RESERVED, WAITING_FOR_PICKUP, READY_FOR_PICKUP, COLLECTED, CANCELLED, EXPIRED }

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** The physical copy set aside for this reservation; null while purely queued. */
    @Column(name = "copy_id", length = 36)
    private String copyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.RESERVED;

    @Column(name = "pickup_deadline_at")
    private Instant pickupDeadlineAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "collected_at")
    private Instant collectedAt;

    /** Loan created when this reservation was collected. */
    @Column(name = "loan_id", length = 36)
    private String loanId;

    @Column(name = "cancelled_reason", length = 200)
    private String cancelledReason;

    public boolean isInFlight() {
        return status == Status.RESERVED || status == Status.WAITING_FOR_PICKUP
                || status == Status.READY_FOR_PICKUP;
    }

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCopyId() { return copyId; }
    public void setCopyId(String copyId) { this.copyId = copyId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getPickupDeadlineAt() { return pickupDeadlineAt; }
    public void setPickupDeadlineAt(Instant pickupDeadlineAt) { this.pickupDeadlineAt = pickupDeadlineAt; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant readyAt) { this.readyAt = readyAt; }
    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }
    public String getLoanId() { return loanId; }
    public void setLoanId(String loanId) { this.loanId = loanId; }
    public String getCancelledReason() { return cancelledReason; }
    public void setCancelledReason(String cancelledReason) { this.cancelledReason = cancelledReason; }
}
