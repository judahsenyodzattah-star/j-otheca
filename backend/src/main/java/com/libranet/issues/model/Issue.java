package com.libranet.issues.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tenant-scoped support issue (audit P2 — spec §17–§23). A patron (or staff on their
 * behalf) reports exactly one of six categories; the conversation threads through
 * {@link IssueMessage}. Numbers are per-tenant and shown as {@code LN-000123}.
 */
@Entity
@Table(name = "issues")
public class Issue extends TenantScopedEntity {

    /** Spec §22 — the only six categories the lane accepts. */
    public enum Category { ACCOUNT_ACCESS, MEMBERSHIP, LOANS, PAYMENTS, TECHNICAL, OTHER }

    /** OPEN → NEEDS_INFO/IN_PROGRESS/ESCALATED → RESOLVED; reporter replies reopen. */
    public enum Status { OPEN, NEEDS_INFO, IN_PROGRESS, RESOLVED, ESCALATED }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    /** APP = typed in the support screen; AI = assistant draft the reporter confirmed (spec §20). */
    public enum Source { APP, AI }

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(name = "reporter_membership_id", nullable = false, length = 36)
    private String reporterMembershipId;

    @Column(name = "reporter_user_id", nullable = false, length = 36)
    private String reporterUserId;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 10000)
    private String description;

    @Column(nullable = false, length = 16)
    private String status = Status.OPEN.name();

    @Column(nullable = false, length = 10)
    private String priority = Priority.NORMAL.name();

    @Column(name = "assignee_membership_id", length = 36)
    private String assigneeMembershipId;

    @Column(nullable = false, length = 8)
    private String source = Source.APP.name();

    @Column(name = "client_key", length = 64)
    private String clientKey;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Speech-friendly reference, e.g. LN-000123 (spec §18). */
    public String ref() {
        return String.format("LN-%06d", issueNumber);
    }

    public int getIssueNumber() { return issueNumber; }
    public void setIssueNumber(int issueNumber) { this.issueNumber = issueNumber; }
    public String getReporterMembershipId() { return reporterMembershipId; }
    public void setReporterMembershipId(String reporterMembershipId) { this.reporterMembershipId = reporterMembershipId; }
    public String getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(String reporterUserId) { this.reporterUserId = reporterUserId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getAssigneeMembershipId() { return assigneeMembershipId; }
    public void setAssigneeMembershipId(String assigneeMembershipId) { this.assigneeMembershipId = assigneeMembershipId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
