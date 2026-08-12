package com.libranet.publishing.service;

import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.circulation.service.NotificationService;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.publishing.dto.PublishingDtos.InterestStateDto;
import com.libranet.publishing.dto.PublishingDtos.InterestedMemberDto;
import com.libranet.publishing.model.BookInterest;
import com.libranet.publishing.repo.BookInterestRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reader interest marks ("I want this") and the staff demand view. Members write; staff read. */
@Service
public class InterestService {

    private final BookInterestRepository interests;
    private final BookRepository books;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final NotificationService notifications;

    public InterestService(BookInterestRepository interests, BookRepository books,
                           MembershipRepository memberships, UserRepository users,
                           NotificationService notifications) {
        this.interests = interests;
        this.books = books;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public InterestStateDto mark(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        Book book = findVisibleBook(ctx, bookId);
        interests.findByTenantIdAndBookIdAndMembershipId(ctx.libraryId(), bookId, ctx.membershipId())
                .orElseGet(() -> {
                    BookInterest i = new BookInterest();
                    i.setTenantId(ctx.libraryId());
                    i.setBookId(bookId);
                    i.setMembershipId(ctx.membershipId());
                    i.setUserId(com.libranet.common.web.SecurityUsers.current().id());
                    BookInterest saved = interests.save(i);
                    // signal the author when the book was written by a platform user (spec §24)
                    if (book.getAuthorUserId() != null) {
                        notifications.notify(ctx.libraryId(), book.getAuthorUserId(), "INTEREST_SIGNAL",
                                "New interest in " + book.getTitle(),
                                "A reader just marked interest in \"" + book.getTitle() + "\".");
                    }
                    return saved;
                });
        return state(ctx.libraryId(), bookId, ctx.membershipId());
    }

    @Transactional
    public InterestStateDto unmark(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        findVisibleBook(ctx, bookId);
        interests.findByTenantIdAndBookIdAndMembershipId(ctx.libraryId(), bookId, ctx.membershipId())
                .ifPresent(interests::delete);
        return state(ctx.libraryId(), bookId, ctx.membershipId());
    }

    @Transactional(readOnly = true)
    public InterestStateDto stateFor(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        findVisibleBook(ctx, bookId);
        return state(ctx.libraryId(), bookId, ctx.membershipId());
    }

    /** Staff: demand list for a title (who wants it). */
    @Transactional(readOnly = true)
    public List<InterestedMemberDto> interestedMembers(String bookId) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        findVisibleBook(ctx, bookId);
        return interests.findByTenantIdAndBookId(ctx.libraryId(), bookId).stream().map(i -> {
            var member = memberships.findById(i.getMembershipId()).orElse(null);
            User user = users.findById(i.getUserId()).orElse(null);
            return InterestedMemberDto.of(i,
                    member == null ? null : member.getMembershipNumber(),
                    user == null ? null : user.getFullName());
        }).toList();
    }

    private InterestStateDto state(String tenantId, String bookId, String membershipId) {
        return new InterestStateDto(bookId,
                interests.findByTenantIdAndBookIdAndMembershipId(tenantId, bookId, membershipId).isPresent(),
                interests.countByTenantIdAndBookId(tenantId, bookId));
    }

    private Book findVisibleBook(TenantContext.Current ctx, String bookId) {
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        if (!book.isPublished()
                && !ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name())) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found");
        }
        return book;
    }

    /** Batch counts for decorating catalog list/detail DTOs. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(String tenantId, List<String> bookIds) {
        Map<String, Long> result = new java.util.HashMap<>();
        if (!bookIds.isEmpty()) {
            for (Object[] row : interests.countByBookGrouped(tenantId, bookIds)) {
                result.put((String) row[0], (Long) row[1]);
            }
        }
        return result;
    }
}
