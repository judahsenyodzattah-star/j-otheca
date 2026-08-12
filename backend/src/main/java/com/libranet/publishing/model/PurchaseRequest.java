package com.libranet.publishing.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** Physical-first sale: member requests a copy, staff hands it over and fulfills (spec §24). */
@Entity
@Table(name = "purchase_requests")
public class PurchaseRequest extends TenantScopedEntity {

    public enum Status { PENDING, FULFILLED, CANCELLED }

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    /** The physical copy handed over; assigned at fulfillment. */
    @Column(name = "copy_id", length = 36)
    private String copyId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    /** Price snapshot at request time — later price edits never retro-change pending requests. */
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency = "GHS";

    @Column(length = 500)
    private String note;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "fulfilled_by_id", length = 36)
    private String fulfilledById;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getCopyId() { return copyId; }
    public void setCopyId(String copyId) { this.copyId = copyId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getPriceMinor() { return priceMinor; }
    public void setPriceMinor(long priceMinor) { this.priceMinor = priceMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public String getFulfilledById() { return fulfilledById; }
    public void setFulfilledById(String fulfilledById) { this.fulfilledById = fulfilledById; }
}
