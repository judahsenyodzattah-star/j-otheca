package com.libranet.circulation.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A charge against a membership (spec §22): LATE (auto-assessed on return), LOST / DAMAGED /
 * REPLACEMENT (assessed by staff, closes the related loan), MANUAL (desk charges).
 * Amounts are minor units + currency — never floats.
 */
@Entity
@Table(name = "fines")
public class Fine extends TenantScopedEntity {

    public enum Type { LATE, LOST, DAMAGED, REPLACEMENT, MANUAL }
    public enum Status { OPEN, PAID, WAIVED }

    @Column(name = "loan_id", length = 36)
    private String loanId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency = "GHS";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(length = 500)
    private String note;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "settled_by_id", length = 36)
    private String settledById;

    /** How it was settled, e.g. "cash at desk" or "waived: first offence". */
    @Column(length = 200)
    private String settlement;

    public String getLoanId() { return loanId; }
    public void setLoanId(String loanId) { this.loanId = loanId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getSettledById() { return settledById; }
    public void setSettledById(String settledById) { this.settledById = settledById; }
    public String getSettlement() { return settlement; }
    public void setSettlement(String settlement) { this.settlement = settlement; }
}
