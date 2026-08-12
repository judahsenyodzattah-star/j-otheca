package com.libranet.media.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.media.dto.ReviewDtos.RatingSummaryDto;
import com.libranet.media.model.BookRating;
import com.libranet.media.repo.BookRatingRepository;
import com.libranet.membership.model.LibraryRole;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1–5 star ratings (Phase 7). One rating per member per book (DB-unique); re-rating updates
 * in place. The denormalized aggregates books.ratingAvg / ratingCount are recomputed after
 * every write so list pages never join the ratings table.
 */
@Service
public class RatingService {

    private final BookRatingRepository ratings;
    private final BookRepository books;
    private final AuditService audit;

    public RatingService(BookRatingRepository ratings, BookRepository books, AuditService audit) {
        this.ratings = ratings;
        this.books = books;
        this.audit = audit;
    }

    /** Upsert the caller's rating for a book. */
    @Transactional
    public RatingSummaryDto rate(String bookId, int stars) {
        TenantContext.Current ctx = TenantContext.require();
        Book book = visibleBook(ctx, bookId);
        boolean[] updated = {false};
        BookRating rating = ratings
                .findByTenantIdAndBookIdAndMembershipId(ctx.libraryId(), bookId, ctx.membershipId())
                .map(existing -> {
                    existing.setStars(stars);
                    updated[0] = true;
                    return existing;
                })
                .orElseGet(() -> {
                    BookRating r = new BookRating();
                    r.setTenantId(ctx.libraryId());
                    r.setBookId(bookId);
                    r.setMembershipId(ctx.membershipId());
                    r.setUserId(SecurityUsers.current().id());
                    r.setStars(stars);
                    return r;
                });
        ratings.saveAndFlush(rating);   // flush now: a unique-race surfaces here, not at commit
        recompute(book);
        audit.log(AuditAction.RATING_SUBMITTED, "Book", bookId,
                Map.of("stars", stars, "updated", updated[0]));
        return summary(ctx.libraryId(), bookId, ctx.membershipId());
    }

    /** Remove the caller's rating (idempotent). */
    @Transactional
    public RatingSummaryDto remove(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        Book book = visibleBook(ctx, bookId);
        var existing = ratings.findByTenantIdAndBookIdAndMembershipId(
                ctx.libraryId(), bookId, ctx.membershipId());
        existing.ifPresent(ratings::delete);
        if (existing.isPresent()) {
            ratings.flush();
            recompute(book);
            audit.log(AuditAction.RATING_DELETED, "Book", bookId, Map.of());
        }
        return summary(ctx.libraryId(), bookId, ctx.membershipId());
    }

    @Transactional(readOnly = true)
    public RatingSummaryDto summaryFor(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        visibleBook(ctx, bookId);
        return summary(ctx.libraryId(), bookId, ctx.membershipId());
    }

    // ---------- internals ----------

    private RatingSummaryDto summary(String tenantId, String bookId, String membershipId) {
        Book book = books.findById(bookId).orElseThrow();
        Integer myStars = ratings.findByTenantIdAndBookIdAndMembershipId(tenantId, bookId, membershipId)
                .map(BookRating::getStars).orElse(null);
        return new RatingSummaryDto(bookId, myStars, book.getRatingAvg(), book.getRatingCount());
    }

    private void recompute(Book book) {
        Double avg = ratings.avgStars(book.getTenantId(), book.getId());
        long count = ratings.countFor(book.getTenantId(), book.getId());
        // round to 2dp so clients display stable values
        book.setRatingAvg(Math.round((avg == null ? 0.0 : avg) * 100.0) / 100.0);
        book.setRatingCount((int) count);
        books.save(book);
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
}
