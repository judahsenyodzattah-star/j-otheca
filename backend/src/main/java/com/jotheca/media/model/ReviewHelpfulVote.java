package com.jotheca.media.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** "Was this review helpful?" — one vote per member per review. */
@Entity
@Table(name = "review_helpful_votes")
public class ReviewHelpfulVote extends TenantScopedEntity {

    @Column(name = "review_id", nullable = false, length = 36)
    private String reviewId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
