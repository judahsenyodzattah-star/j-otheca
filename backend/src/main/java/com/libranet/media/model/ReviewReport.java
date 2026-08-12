package com.libranet.media.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** An abuse report filed against a review; one report per member per review. */
@Entity
@Table(name = "review_reports")
public class ReviewReport extends TenantScopedEntity {

    public enum ReportStatus { OPEN, RESOLVED, DISMISSED }

    @Column(name = "review_id", nullable = false, length = 36)
    private String reviewId;

    @Column(name = "reporter_membership_id", nullable = false, length = 36)
    private String reporterMembershipId;

    @Column(name = "reporter_user_id", nullable = false, length = 36)
    private String reporterUserId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReportStatus status = ReportStatus.OPEN;

    @Column(name = "resolved_by_id", length = 36)
    private String resolvedById;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }
    public String getReporterMembershipId() { return reporterMembershipId; }
    public void setReporterMembershipId(String reporterMembershipId) { this.reporterMembershipId = reporterMembershipId; }
    public String getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(String reporterUserId) { this.reporterUserId = reporterUserId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus status) { this.status = status; }
    public String getResolvedById() { return resolvedById; }
    public void setResolvedById(String resolvedById) { this.resolvedById = resolvedById; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
