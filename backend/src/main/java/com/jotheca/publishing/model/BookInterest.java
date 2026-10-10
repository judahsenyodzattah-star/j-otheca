package com.jotheca.publishing.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** "I want this" — a reader's interest mark on a title (demand signal, spec §24). */
@Entity
@Table(name = "book_interests")
public class BookInterest extends TenantScopedEntity {

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
