package com.libranet.authors.service;

import com.libranet.authors.dto.AuthorDtos.AuthorStudioDto;
import com.libranet.authors.dto.AuthorDtos.StudioBookDto;
import com.libranet.authors.dto.AuthorDtos.StudioTotals;
import com.libranet.authors.model.AuthorProfile;
import com.libranet.authors.repo.AuthorProfileRepository;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.web.SecurityUsers;
import com.libranet.publishing.model.BookInterest;
import com.libranet.publishing.model.PurchaseRequest;
import com.libranet.publishing.repo.BookInterestRepository;
import com.libranet.publishing.repo.PurchaseRequestRepository;
import com.libranet.tenancy.repo.LibraryRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Author Studio (E17): the author's own cross-library view. It aggregates ONLY rows linked
 * to the caller's own author-profile id — member identities are never projected, just
 * counts and money. Runs under /me (no tenant header), so pricing/member privacy rules of
 * individual tenant lanes don't apply — this lane ships aggregates, not rows.
 */
@Service
public class AuthorStudioService {

    private final AuthorProfileRepository profiles;
    private final BookRepository books;
    private final BookInterestRepository interests;
    private final PurchaseRequestRepository purchases;
    private final LibraryRepository libraries;

    public AuthorStudioService(AuthorProfileRepository profiles, BookRepository books,
                               BookInterestRepository interests, PurchaseRequestRepository purchases,
                               LibraryRepository libraries) {
        this.profiles = profiles;
        this.books = books;
        this.interests = interests;
        this.purchases = purchases;
        this.libraries = libraries;
    }

    @Transactional(readOnly = true)
    public AuthorStudioDto studio() {
        AuthorProfile p = profiles.findByUserId(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.notFound("AUTHOR_PROFILE_REQUIRED",
                        "No author profile yet — open one from Settings → Author studio."));

        List<Book> mine = books.findByAuthorProfileId(p.getId());
        List<String> ids = mine.stream().map(Book::getId).toList();

        Map<String, Long> interestByBook = ids.isEmpty() ? Map.of()
                : interests.findByBookIdIn(ids).stream()
                        .collect(Collectors.groupingBy(BookInterest::getBookId, Collectors.counting()));
        Map<String, List<PurchaseRequest>> purchaseByBook = ids.isEmpty() ? Map.of()
                : purchases.findByBookIdIn(ids).stream()
                        .collect(Collectors.groupingBy(PurchaseRequest::getBookId));

        List<StudioBookDto> bookRows = mine.stream().map(b -> {
            List<PurchaseRequest> reqs = purchaseByBook.getOrDefault(b.getId(), List.of());
            long pending = reqs.stream().filter(r -> r.getStatus() == PurchaseRequest.Status.PENDING).count();
            long sold = reqs.stream().filter(r -> r.getStatus() == PurchaseRequest.Status.FULFILLED).count();
            long revenue = reqs.stream()
                    .filter(r -> r.getStatus() == PurchaseRequest.Status.FULFILLED)
                    .mapToLong(PurchaseRequest::getPriceMinor).sum();
            var lib = libraries.findById(b.getTenantId()).orElse(null);
            return new StudioBookDto(b.getId(), b.getTitle(),
                    lib == null ? "A library" : lib.getName(),
                    lib == null ? null : lib.getSlug(),
                    interestByBook.getOrDefault(b.getId(), 0L), pending, sold, revenue, "GHS",
                    b.getSalePriceMinor(), b.getRatingAvg(), b.getRatingCount());
        }).toList();

        StudioTotals totals = new StudioTotals(
                bookRows.size(),
                bookRows.stream().mapToLong(StudioBookDto::interestedCount).sum(),
                bookRows.stream().mapToLong(StudioBookDto::pendingRequests).sum(),
                bookRows.stream().mapToLong(StudioBookDto::copiesSold).sum(),
                bookRows.stream().mapToLong(StudioBookDto::revenueMinor).sum(),
                "GHS",
                bookRows.stream().mapToLong(StudioBookDto::ratingCount).sum());
        return new AuthorStudioDto(p.getSlug(), p.getPenName(), p.isPublicProfile(),
                p.isSellingEnabled(), totals, bookRows);
    }
}
