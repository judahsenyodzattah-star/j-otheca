package com.libranet.media.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.media.dto.ReviewDtos.HelpfulStateDto;
import com.libranet.media.dto.ReviewDtos.ReportDto;
import com.libranet.media.dto.ReviewDtos.ReviewDto;
import com.libranet.media.dto.ReviewDtos.ReviewPolicyDto;
import com.libranet.media.dto.ReviewDtos.UpsertReviewRequest;
import com.libranet.media.model.BookReview;
import com.libranet.media.model.BookReview.ReviewStatus;
import com.libranet.media.model.ReviewHelpfulVote;
import com.libranet.media.model.ReviewReport;
import com.libranet.media.model.ReviewReport.ReportStatus;
import com.libranet.media.repo.BookReviewRepository;
import com.libranet.media.repo.ReviewHelpfulVoteRepository;
import com.libranet.media.repo.ReviewReportRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import com.libranet.tenancy.repo.LibraryRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * Reviews & moderation (Phase 7).
 *
 *  - Members write; staff moderate (approve/reject) through a queue; abuse flows through reports.
 *  - One non-deleted review per member per book; edits re-enter moderation (status back to PENDING);
 *    delete is a status flag — rows are never destroyed.
 *  - Anonymous display honors the author's choice ONLY when the library policy allows it; the real
 *    identity always stays visible to staff.
 *  - Cross-tenant or cross-owner access answers 404 — existence is never revealed.
 */
@Service
public class ReviewService {

    private static final String ANONYMOUS_NAME = "Anonymous member";

    private final BookReviewRepository reviews;
    private final ReviewReportRepository reports;
    private final ReviewHelpfulVoteRepository votes;
    private final BookRepository books;
    private final LibraryRepository libraries;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final AuditService audit;

        private final PermissionService permissions;

    public ReviewService(BookReviewRepository reviews, ReviewReportRepository reports,
                         ReviewHelpfulVoteRepository votes, BookRepository books,
                         LibraryRepository libraries, MembershipRepository memberships,
                         UserRepository users, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.reviews = reviews;
        this.reports = reports;
        this.votes = votes;
        this.books = books;
        this.libraries = libraries;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
    }

    // ---------- member writes ----------

    /** Create a review, or reactivate the member's soft-deleted one. */
    @Transactional
    public ReviewDto create(String bookId, UpsertReviewRequest req) {
        TenantContext.Current ctx = TenantContext.require();
        visibleBook(ctx, bookId);
        if (req.anonymous()) {
            requireAnonymousAllowed(ctx.libraryId());
        }
        var existing = reviews.findByTenantIdAndBookIdAndMembershipId(
                ctx.libraryId(), bookId, ctx.membershipId());
        BookReview review;
        if (existing.isPresent()) {
            review = existing.get();
            if (review.getStatus() != ReviewStatus.DELETED) {
                throw ApiException.conflict("REVIEW_EXISTS",
                        "You already reviewed this book. Edit your existing review instead.");
            }
            apply(review, req);   // reactivation after soft-delete keeps the unique key simple
        } else {
            review = new BookReview();
            review.setTenantId(ctx.libraryId());
            review.setBookId(bookId);
            review.setMembershipId(ctx.membershipId());
            review.setUserId(SecurityUsers.current().id());
            apply(review, req);
        }
        reviews.saveAndFlush(review);   // unique-race on (tenant, book, membership) surfaces here
        audit.log(AuditAction.REVIEW_SUBMITTED, "BookReview", review.getId(),
                Map.of("bookId", bookId, "anonymous", review.isAnonymous()));
        return toDto(review, false, ctx, null);
    }

    /** Edit own review (404 for others' reviews — existence is hidden). Re-enters moderation. */
    @Transactional
    public ReviewDto update(String id, UpsertReviewRequest req) {
        TenantContext.Current ctx = TenantContext.require();
        BookReview review = ownReview(ctx, id);
        if (review.getStatus() == ReviewStatus.DELETED) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        if (req.anonymous()) {
            requireAnonymousAllowed(ctx.libraryId());
        }
        apply(review, req);
        reviews.save(review);
        audit.log(AuditAction.REVIEW_UPDATED, "BookReview", id, Map.of());
        return toDto(review, false, ctx, null);
    }

    /** Soft-delete own review (404 for others'). */
    @Transactional
    public void delete(String id) {
        TenantContext.Current ctx = TenantContext.require();
        BookReview review = ownReview(ctx, id);
        if (review.getStatus() == ReviewStatus.DELETED) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        review.setStatus(ReviewStatus.DELETED);
        reviews.save(review);
        audit.log(AuditAction.REVIEW_DELETED, "BookReview", id, Map.of());
    }

    // ---------- member reads ----------

    /** Book page feed: APPROVED reviews plus the caller's own (any non-deleted status). */
    @Transactional(readOnly = true)
    public Page<ReviewDto> listForBook(String bookId, Pageable pageable) {
        TenantContext.Current ctx = TenantContext.require();
        visibleBook(ctx, bookId);
        boolean staffView = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        Page<BookReview> page;
        if (staffView) {
            // staff see everything except deleted — this doubles as a light-weight queue per book
            page = reviews.findAll((root, q, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), ctx.libraryId()),
                    cb.equal(root.get("bookId"), bookId),
                    cb.notEqual(root.get("status"), ReviewStatus.DELETED)), sorted(pageable));
        } else {
            page = reviews.findAll((root, q, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), ctx.libraryId()),
                    cb.equal(root.get("bookId"), bookId),
                    cb.or(cb.equal(root.get("status"), ReviewStatus.APPROVED),
                          cb.and(cb.equal(root.get("membershipId"), ctx.membershipId()),
                                 cb.notEqual(root.get("status"), ReviewStatus.DELETED)))), sorted(pageable));
        }
        Map<String, Boolean> myVotes = myVotes(ctx.libraryId(), ctx.membershipId(), page.getContent());
        return page.map(r -> toDto(r, staffView, ctx, myVotes.get(r.getId())));
    }

    // ---------- staff moderation ----------

    /** Staff queue, filterable by status (default PENDING). Real identities, open-report counts. */
    @Transactional(readOnly = true)
    public Page<ReviewDto> moderationQueue(ReviewStatus status, Pageable pageable) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.REVIEWS_MODERATE);
        ReviewStatus effective = status == null ? ReviewStatus.PENDING : status;
        Page<BookReview> page = reviews.findByTenantIdAndStatus(ctx.libraryId(), effective, sorted(pageable));
        return page.map(r -> toDto(r, true, ctx, null,
                reports.countByTenantIdAndReviewIdAndStatus(ctx.libraryId(), r.getId(), ReportStatus.OPEN)));
    }

    @Transactional
    public ReviewDto approve(String id) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.REVIEWS_MODERATE);
        BookReview review = staffReview(ctx, id);
        review.setStatus(ReviewStatus.APPROVED);
        review.setModeratedById(ctx.membershipId());
        review.setModeratedAt(Instant.now());
        review.setModerationNote(null);
        reviews.save(review);
        resolveReports(ctx, id, ReportStatus.RESOLVED);
        audit.log(AuditAction.REVIEW_APPROVED, "BookReview", id, Map.of("bookId", review.getBookId()));
        return toDto(review, true, ctx, null);
    }

    @Transactional
    public ReviewDto reject(String id, String note) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.REVIEWS_MODERATE);
        BookReview review = staffReview(ctx, id);
        review.setStatus(ReviewStatus.REJECTED);
        review.setModeratedById(ctx.membershipId());
        review.setModeratedAt(Instant.now());
        review.setModerationNote(note == null || note.isBlank() ? null : note);
        reviews.save(review);
        resolveReports(ctx, id, ReportStatus.RESOLVED);
        audit.log(AuditAction.REVIEW_REJECTED, "BookReview", id,
                Map.of("bookId", review.getBookId(), "note", review.getModerationNote() == null ? "" : review.getModerationNote()));
        return toDto(review, true, ctx, null);
    }

    // ---------- reports ----------

    /** File an abuse report against a visible APPROVED review (not your own). */
    @Transactional
    public ReportDto report(String reviewId, String reason) {
        TenantContext.Current ctx = TenantContext.require();
        BookReview review = reviews.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found"));
        TenantGuard.requireBelongsToCurrentTenant(review.getTenantId());
        if (review.getStatus() != ReviewStatus.APPROVED) {
            // you cannot report what you cannot see
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        if (review.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.badRequest("CANNOT_REPORT_OWN", "You cannot report your own review.");
        }
        reports.findByTenantIdAndReviewIdAndReporterMembershipId(ctx.libraryId(), reviewId, ctx.membershipId())
                .ifPresent(r -> {
                    throw ApiException.conflict("REPORT_EXISTS", "You already reported this review.");
                });
        ReviewReport report = new ReviewReport();
        report.setTenantId(ctx.libraryId());
        report.setReviewId(reviewId);
        report.setReporterMembershipId(ctx.membershipId());
        report.setReporterUserId(SecurityUsers.current().id());
        report.setReason(reason);
        reports.saveAndFlush(report);
        audit.log(AuditAction.REVIEW_REPORTED, "BookReview", reviewId, Map.of("reason", reason));
        return new ReportDto(report.getId(), reviewId, reason, report.getStatus().name(), report.getCreatedAt());
    }

    /** Staff: dismiss all open reports on a review without acting on the review itself. */
    @Transactional
    public void dismissReports(String reviewId) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.REVIEWS_MODERATE);
        staffReview(ctx, reviewId);
        resolveReports(ctx, reviewId, ReportStatus.DISMISSED);
    }

    // ---------- helpful votes ----------

    @Transactional
    public HelpfulStateDto markHelpful(String reviewId) {
        TenantContext.Current ctx = TenantContext.require();
        BookReview review = votableReview(ctx, reviewId);
        var existing = votes.findByTenantIdAndReviewIdAndMembershipId(ctx.libraryId(), reviewId, ctx.membershipId());
        if (existing.isEmpty()) {
            ReviewHelpfulVote vote = new ReviewHelpfulVote();
            vote.setTenantId(ctx.libraryId());
            vote.setReviewId(reviewId);
            vote.setMembershipId(ctx.membershipId());
            vote.setUserId(SecurityUsers.current().id());
            votes.saveAndFlush(vote);
            review.setHelpfulCount(review.getHelpfulCount() + 1);
            reviews.save(review);
        }
        return new HelpfulStateDto(reviewId, true, review.getHelpfulCount());
    }

    @Transactional
    public HelpfulStateDto unmarkHelpful(String reviewId) {
        TenantContext.Current ctx = TenantContext.require();
        BookReview review = votableReview(ctx, reviewId);
        votes.findByTenantIdAndReviewIdAndMembershipId(ctx.libraryId(), reviewId, ctx.membershipId())
                .ifPresent(vote -> {
                    votes.delete(vote);
                    review.setHelpfulCount(Math.max(0, review.getHelpfulCount() - 1));
                    reviews.save(review);
                });
        return new HelpfulStateDto(reviewId, false, review.getHelpfulCount());
    }

    // ---------- review policy ----------

    @Transactional(readOnly = true)
    public ReviewPolicyDto policy() {
        TenantContext.Current ctx = TenantContext.require();
        return new ReviewPolicyDto(library(ctx.libraryId()).isAllowAnonymousReviews());
    }

    /** LIBRARY_ADMIN: toggle anonymous reviews. */
    @Transactional
    public ReviewPolicyDto updatePolicy(boolean allowAnonymous) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Library library = library(ctx.libraryId());
        library.setAllowAnonymousReviews(allowAnonymous);
        libraries.save(library);
        audit.log(AuditAction.REVIEW_POLICY_UPDATED, "Library", library.getId(),
                Map.of("allowAnonymousReviews", allowAnonymous));
        return new ReviewPolicyDto(allowAnonymous);
    }

    // ---------- public plane (no TenantContext; explicit scoping) ----------

    /** Approved reviews for the public book page. Anonymous stays anonymous here too. */
    @Transactional(readOnly = true)
    public Page<ReviewDto> publicList(String librarySlug, String bookSlug, Pageable pageable) {
        Library library = libraries.findBySlug(librarySlug).orElse(null);
        if (library == null || library.getStatus() != LibraryStatus.ACTIVE || !library.isVisibility()) {
            throw ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found");
        }
        Book book = books.findByTenantIdAndSlug(library.getId(), bookSlug)
                .filter(Book::isPublished)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        Specification<BookReview> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), library.getId()));
            ps.add(cb.equal(root.get("bookId"), book.getId()));
            ps.add(cb.equal(root.get("status"), ReviewStatus.APPROVED));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return reviews.findAll(spec, sorted(pageable)).map(r -> toDto(r, false, null, null));
    }

    // ---------- internals ----------

    private void apply(BookReview review, UpsertReviewRequest req) {
        review.setTitle(req.title() == null || req.title().isBlank() ? null : req.title().trim());
        review.setBody(req.body().trim());
        review.setAnonymous(req.anonymous());
        review.setStatus(ReviewStatus.PENDING);
        review.setModeratedById(null);
        review.setModeratedAt(null);
        review.setModerationNote(null);
    }

    private void requireAnonymousAllowed(String libraryId) {
        if (!library(libraryId).isAllowAnonymousReviews()) {
            throw ApiException.forbidden("ANONYMOUS_REVIEWS_NOT_ALLOWED",
                    "This library does not allow anonymous reviews.");
        }
    }

    private Library library(String libraryId) {
        return libraries.findById(libraryId)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
    }

    /** Own review or 404 (existence in this tenant is hidden from other members). */
    private BookReview ownReview(TenantContext.Current ctx, String id) {
        BookReview review = reviews.findById(id)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found"));
        TenantGuard.requireBelongsToCurrentTenant(review.getTenantId());
        if (!review.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        return review;
    }

    private BookReview staffReview(TenantContext.Current ctx, String id) {
        BookReview review = reviews.findById(id)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found"));
        TenantGuard.requireBelongsToCurrentTenant(review.getTenantId());
        return review;
    }

    private BookReview votableReview(TenantContext.Current ctx, String reviewId) {
        BookReview review = staffReview(ctx, reviewId);
        if (review.getStatus() != ReviewStatus.APPROVED) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        if (review.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.badRequest("CANNOT_VOTE_OWN", "You cannot vote on your own review.");
        }
        return review;
    }

    private void resolveReports(TenantContext.Current ctx, String reviewId, ReportStatus outcome) {
        for (ReviewReport report : reports.findByTenantIdAndReviewIdAndStatus(
                ctx.libraryId(), reviewId, ReportStatus.OPEN)) {
            report.setStatus(outcome);
            report.setResolvedById(ctx.membershipId());
            report.setResolvedAt(Instant.now());
            reports.save(report);
        }
    }

    private Book visibleBook(TenantContext.Current ctx, String bookId) {
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        if (!book.isPublished()
                && !ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name())) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found");
        }
        return book;
    }

    private Pageable sorted(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private Map<String, Boolean> myVotes(String tenantId, String membershipId, List<BookReview> page) {
        Map<String, Boolean> result = new HashMap<>();
        for (BookReview r : page) {
            result.put(r.getId(), votes.findByTenantIdAndReviewIdAndMembershipId(
                    tenantId, r.getId(), membershipId).isPresent());
        }
        return result;
    }

    private ReviewDto toDto(BookReview r, boolean staffView, TenantContext.Current ctx, Boolean myHelpful) {
        return toDto(r, staffView, ctx, myHelpful, null);
    }

    private ReviewDto toDto(BookReview r, boolean staffView, TenantContext.Current ctx,
                            Boolean myHelpful, Long openReports) {
        boolean mine = ctx != null && r.getMembershipId().equals(ctx.membershipId());
        boolean showIdentity = staffView || mine || !r.isAnonymous();
        String displayName = ANONYMOUS_NAME;
        String membershipNumber = null;
        if (showIdentity) {
            displayName = users.findById(r.getUserId()).map(User::getFullName).orElse("Unknown member");
            membershipNumber = memberships.findById(r.getMembershipId())
                    .map(Membership::getMembershipNumber).orElse(null);
        }
        Integer reportsCount = staffView && openReports != null ? openReports.intValue() : null;
        String moderationNote = (staffView || mine) ? r.getModerationNote() : null;
        return new ReviewDto(r.getId(), r.getBookId(), r.getTitle(), r.getBody(),
                r.isAnonymous() && !showIdentity, displayName, membershipNumber,
                r.getStatus().name(), r.getHelpfulCount(), mine, Boolean.TRUE.equals(myHelpful),
                reportsCount, moderationNote, r.getCreatedAt(), r.getUpdatedAt());
    }
}
