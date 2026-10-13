package com.jotheca.media.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A member's long-form review. Lifecycle: PENDING → APPROVED | REJECTED (staff moderation);
 * DELETED is the soft-delete flag (data is never destroyed). Edits by the author reset to
 * PENDING so changed text is re-moderated. At most one non-deleted review per member per book —
 * re-posting after deletion reactivates the same row, which keeps the unique key simple.
 */
@Entity
@Table(name = "book_reviews")
public class BookReview extends TenantScopedEntity {

    public enum ReviewStatus { PENDING, APPROVED, REJECTED, DELETED }

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Displayed as "Anonymous member" to non-staff; the real identity stays staff-visible. */
    @Column(nullable = false)
    private boolean anonymous;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "moderation_note", length = 500)
    private String moderationNote;

    @Column(name = "moderated_by_id", length = 36)
    private String moderatedById;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "helpful_count", nullable = false)
    private int helpfulCount;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getMembershipId() { return membershipId; }
    public void setMembershipId(String membershipId) { this.membershipId = membershipId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isAnonymous() { return anonymous; }
    public void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }
    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }
    public String getModerationNote() { return moderationNote; }
    public void setModerationNote(String moderationNote) { this.moderationNote = moderationNote; }
    public String getModeratedById() { return moderatedById; }
    public void setModeratedById(String moderatedById) { this.moderatedById = moderatedById; }
    public Instant getModeratedAt() { return moderatedAt; }
    public void setModeratedAt(Instant moderatedAt) { this.moderatedAt = moderatedAt; }
    public int getHelpfulCount() { return helpfulCount; }
    public void setHelpfulCount(int helpfulCount) { this.helpfulCount = helpfulCount; }
}
