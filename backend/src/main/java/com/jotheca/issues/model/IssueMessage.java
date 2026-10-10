package com.jotheca.issues.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One message on an issue thread. {@code internal=true} marks a staff-only note that
 * the reporter (and the assistant) can never see — enforced in the service layer, not
 * just hidden in the UI (audit rule: hidden UI is NOT security).
 */
@Entity
@Table(name = "issue_messages")
public class IssueMessage extends TenantScopedEntity {

    @Column(name = "issue_id", nullable = false, length = 36)
    private String issueId;

    /** NULL for system/AI-authored rows (e.g. the assistant's info-request echo). */
    @Column(name = "author_membership_id", length = 36)
    private String authorMembershipId;

    @Column(name = "author_user_id", length = 36)
    private String authorUserId;

    @Column(nullable = false, length = 10000)
    private String body;

    @Column(nullable = false)
    private boolean internal;

    public String getIssueId() { return issueId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }
    public String getAuthorMembershipId() { return authorMembershipId; }
    public void setAuthorMembershipId(String authorMembershipId) { this.authorMembershipId = authorMembershipId; }
    public String getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(String authorUserId) { this.authorUserId = authorUserId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isInternal() { return internal; }
    public void setInternal(boolean internal) { this.internal = internal; }
}
