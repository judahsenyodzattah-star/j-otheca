package com.jotheca.issues.dto;

import com.jotheca.issues.model.Issue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class IssueDtos {

    private IssueDtos() {}

    /**
     * Minimal data collection (spec §19): category + what happened. Reporter identity is
     * derived from the caller's membership — the form never asks for name/email again.
     * {@code clientKey} makes client retries idempotent; {@code source}=AI marks an
     * assistant-drafted issue the reporter explicitly confirmed (spec §20).
     */
    public record CreateIssueRequest(
            @NotBlank @Size(max = 20) String category,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String description,
            @Size(max = 64) String clientKey,
            @Size(max = 8) String source) {}

    public record IssueMessageRequest(@NotBlank @Size(max = 10000) String body,
                                      Boolean internal) {}

    public record AssignRequest(@Size(max = 36) String assigneeMembershipId) {} // null/"me" allowed

    public record PriorityRequest(@NotBlank @Size(max = 10) String priority) {}

    public record NoteRequest(@NotBlank @Size(max = 10000) String note) {}

    public record IssueDto(
            String id, String ref, String category, String subject, String status, String priority,
            String source, String reporterMembershipId, String reporterName, String reporterNumber,
            String assigneeMembershipId, String assigneeName,
            Instant createdAt, Instant updatedAt, Instant resolvedAt, long messageCount) {

        public static IssueDto from(Issue i, String reporterName, String reporterNumber,
                                    String assigneeName, long messageCount) {
            return new IssueDto(i.getId(), i.ref(), i.getCategory(), i.getSubject(), i.getStatus(),
                    i.getPriority(), i.getSource(), i.getReporterMembershipId(), reporterName,
                    reporterNumber, i.getAssigneeMembershipId(), assigneeName,
                    i.getCreatedAt(), i.getUpdatedAt(), i.getResolvedAt(), messageCount);
        }
    }

    public record IssueMessageDto(String id, String authorUserId, String authorName,
                                  boolean staffAuthored, boolean internal, boolean system,
                                  String body, Instant createdAt) {}

    /** Detail payload: description stays on the issue row; thread is separate. */
    public record IssueDetailDto(IssueDto issue, String description, List<IssueMessageDto> messages) {}
}
