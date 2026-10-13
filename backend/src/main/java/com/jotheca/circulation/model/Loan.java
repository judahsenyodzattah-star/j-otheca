package com.jotheca.circulation.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** One copy out on loan to one membership. OVERDUE is derived (ACTIVE & past due) — never stored. */
@Entity
@Table(name = "loans")
public class Loan extends TenantScopedEntity {

    public enum Status { ACTIVE, RETURNED }

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "copy_id", nullable = false, length = 36)
    private String copyId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Staff user id who issued the loan at the desk. */
    @Column(name = "issued_by_id", nullable = false, length = 36)
    private String issuedById;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "renewed_count", nullable = false)
    private int renewedCount;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "returned_by_id", length = 36)
    private String returnedById;

    /** When an overdue reminder was last sent for this loan — makes the lazy reminder sweep idempotent. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    public boolean isOverdueAt(Instant now) {
        return status == Status.ACTIVE && dueAt.isBefore(now);
    }

    public Instant getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(Instant reminderSentAt) { this.reminderSentAt = reminderSentAt; }

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getCopyId() { return copyId; }
    public void setCopyId(String copyId) { this.copyId = copyId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIssuedById() { return issuedById; }
    public void setIssuedById(String issuedById) { this.issuedById = issuedById; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public int getRenewedCount() { return renewedCount; }
    public void setRenewedCount(int renewedCount) { this.renewedCount = renewedCount; }
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant returnedAt) { this.returnedAt = returnedAt; }
    public String getReturnedById() { return returnedById; }
    public void setReturnedById(String returnedById) { this.returnedById = returnedById; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
