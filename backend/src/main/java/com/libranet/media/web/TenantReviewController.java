package com.libranet.media.web;

import com.libranet.media.dto.ReviewDtos.HelpfulStateDto;
import com.libranet.media.dto.ReviewDtos.RateRequest;
import com.libranet.media.dto.ReviewDtos.RatingSummaryDto;
import com.libranet.media.dto.ReviewDtos.RejectRequest;
import com.libranet.media.dto.ReviewDtos.ReportDto;
import com.libranet.media.dto.ReviewDtos.ReportRequest;
import com.libranet.media.dto.ReviewDtos.ReviewDto;
import com.libranet.media.dto.ReviewDtos.ReviewPolicyDto;
import com.libranet.media.dto.ReviewDtos.ReviewPolicyRequest;
import com.libranet.media.dto.ReviewDtos.UpsertReviewRequest;
import com.libranet.media.model.BookReview.ReviewStatus;
import com.libranet.media.service.RatingService;
import com.libranet.media.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant plane: ratings, reviews, moderation, reports, helpful votes, review policy (Phase 7). */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantReviewController {

    private final RatingService ratings;
    private final ReviewService reviews;

    public TenantReviewController(RatingService ratings, ReviewService reviews) {
        this.ratings = ratings;
        this.reviews = reviews;
    }

    // ---------- ratings ----------

    @PutMapping("/catalog/books/{bookId}/rating")
    public RatingSummaryDto rate(@PathVariable String bookId, @Valid @RequestBody RateRequest req) {
        return ratings.rate(bookId, req.stars());
    }

    @DeleteMapping("/catalog/books/{bookId}/rating")
    public RatingSummaryDto unrate(@PathVariable String bookId) {
        return ratings.remove(bookId);
    }

    @GetMapping("/catalog/books/{bookId}/rating")
    public RatingSummaryDto rating(@PathVariable String bookId) {
        return ratings.summaryFor(bookId);
    }

    // ---------- reviews (member) ----------

    @PostMapping("/catalog/books/{bookId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto create(@PathVariable String bookId, @Valid @RequestBody UpsertReviewRequest req) {
        return reviews.create(bookId, req);
    }

    @GetMapping("/catalog/books/{bookId}/reviews")
    public Page<ReviewDto> list(@PathVariable String bookId, Pageable pageable) {
        return reviews.listForBook(bookId, pageable);
    }

    @PatchMapping("/reviews/{id}")
    public ReviewDto update(@PathVariable String id, @Valid @RequestBody UpsertReviewRequest req) {
        return reviews.update(id, req);
    }

    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        reviews.delete(id);
    }

    // ---------- moderation (staff) ----------

    @GetMapping("/reviews/moderation")
    public Page<ReviewDto> moderation(@RequestParam(required = false) ReviewStatus status, Pageable pageable) {
        return reviews.moderationQueue(status, pageable);
    }

    @PostMapping("/reviews/{id}/approve")
    public ReviewDto approve(@PathVariable String id) {
        return reviews.approve(id);
    }

    @PostMapping("/reviews/{id}/reject")
    public ReviewDto reject(@PathVariable String id, @Valid @RequestBody(required = false) RejectRequest req) {
        return reviews.reject(id, req == null ? null : req.note());
    }

    // ---------- reports ----------

    @PostMapping("/reviews/{id}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDto report(@PathVariable String id, @Valid @RequestBody ReportRequest req) {
        return reviews.report(id, req.reason());
    }

    @PostMapping("/reviews/{id}/reports/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissReports(@PathVariable String id) {
        reviews.dismissReports(id);
    }

    // ---------- helpful votes ----------

    @PutMapping("/reviews/{id}/helpful")
    public HelpfulStateDto helpful(@PathVariable String id) {
        return reviews.markHelpful(id);
    }

    @DeleteMapping("/reviews/{id}/helpful")
    public HelpfulStateDto unhelpful(@PathVariable String id) {
        return reviews.unmarkHelpful(id);
    }

    // ---------- policy ----------

    @GetMapping("/review-policy")
    public ReviewPolicyDto policy() {
        return reviews.policy();
    }

    /** LIBRARY_ADMIN: allow/forbid anonymous reviews. */
    @PutMapping("/review-policy")
    public ReviewPolicyDto updatePolicy(@Valid @RequestBody ReviewPolicyRequest req) {
        return reviews.updatePolicy(req.allowAnonymousReviews());
    }
}
