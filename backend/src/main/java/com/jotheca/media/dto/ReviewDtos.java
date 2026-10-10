package com.jotheca.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ReviewDtos {

    private ReviewDtos() {}

    // ---------- ratings ----------

    public record RateRequest(@NotNull @Min(1) @Max(5) Integer stars) {}

    /** Rating state for one book: the viewer's own stars plus the live aggregates. */
    public record RatingSummaryDto(String bookId, Integer myStars, double ratingAvg, int ratingCount) {}

    // ---------- reviews ----------

    public record UpsertReviewRequest(@Size(max = 200) String title,
                                      @NotBlank @Size(max = 5000) String body,
                                      boolean anonymous) {}

    public record RejectRequest(@Size(max = 500) String note) {}

    public record ReportRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReviewPolicyRequest(boolean allowAnonymousReviews) {}

    public record ReviewPolicyDto(boolean allowAnonymousReviews) {}

    /**
     * A review as a member sees it. Anonymous reviews hide the author from non-staff;
     * `mine` lets the client render edit/delete affordances; moderation fields are staff-only.
     */
    public record ReviewDto(String id, String bookId, String title, String body,
                            boolean anonymous, String displayName, String membershipNumber,
                            String status, int helpfulCount, boolean mine, boolean myHelpful,
                            Integer openReports, String moderationNote,
                            Instant createdAt, Instant updatedAt) {}

    public record HelpfulStateDto(String reviewId, boolean helpful, int helpfulCount) {}

    public record ReportDto(String id, String reviewId, String reason, String status, Instant createdAt) {}
}
