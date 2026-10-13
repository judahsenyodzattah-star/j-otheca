package com.jotheca.media.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One member's 1–5 star rating of a book. Unique per (tenant, book, membership). */
@Entity
@Table(name = "book_ratings")
public class BookRating extends TenantScopedEntity {

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private int stars;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }
}
