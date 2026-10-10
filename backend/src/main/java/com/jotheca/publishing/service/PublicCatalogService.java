package com.jotheca.publishing.service;

import com.jotheca.catalog.model.Book;
import com.jotheca.catalog.model.Category;
import com.jotheca.catalog.model.CopyStatus;
import com.jotheca.catalog.repo.BookCopyRepository;
import com.jotheca.catalog.repo.BookRepository;
import com.jotheca.catalog.repo.CategoryRepository;
import com.jotheca.common.error.ApiException;
import com.jotheca.publishing.dto.PublicBookDtos.PublicBookDto;
import com.jotheca.publishing.dto.PublicBookDtos.PublicBookSummaryDto;
import com.jotheca.publishing.repo.BookInterestRepository;
import com.jotheca.tenancy.model.Library;
import com.jotheca.tenancy.model.LibraryStatus;
import com.jotheca.tenancy.repo.LibraryRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public, unauthenticated book pages & per-library catalog browse (spec §24).
 * No TenantContext is armed on these endpoints — scoping is explicit by library slug,
 * and only ACTIVE + directory-visible libraries with PUBLISHED books are ever exposed.
 */
@Service
public class PublicCatalogService {

    private final LibraryRepository libraries;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final CategoryRepository categories;
    private final BookInterestRepository interests;
    private final String publicBaseUrl;

    public PublicCatalogService(LibraryRepository libraries, BookRepository books,
                                BookCopyRepository copies, CategoryRepository categories,
                                BookInterestRepository interests,
                                @Value("${app.public-base-url:https://jotheca.app}") String publicBaseUrl) {
        this.libraries = libraries;
        this.books = books;
        this.copies = copies;
        this.categories = categories;
        this.interests = interests;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional(readOnly = true)
    public Page<PublicBookSummaryDto> search(String librarySlug, String q, Pageable pageable) {
        Library library = publicLibrary(librarySlug);
        Specification<Book> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), library.getId()));
            ps.add(cb.isTrue(root.get("published")));
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("subtitle")), like),
                        cb.like(cb.lower(root.get("author")), like),
                        cb.like(cb.lower(root.get("subjects")), like),
                        cb.like(cb.lower(root.get("isbn")), like)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Page<Book> page = books.findAll(spec, pageable);
        Map<String, String> categoryNames = categories
                .findByTenantIdOrderBySortOrderAscNameAsc(library.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<String, Long> available = availability(library.getId(),
                page.getContent().stream().map(Book::getId).toList());
        return page.map(b -> PublicBookSummaryDto.of(b, library, categoryNames.get(b.getCategoryId()),
                available.getOrDefault(b.getId(), 0L), publicBaseUrl));
    }

    @Transactional(readOnly = true)
    public PublicBookDto detail(String librarySlug, String bookSlug) {
        Library library = publicLibrary(librarySlug);
        Book book = books.findByTenantIdAndSlug(library.getId(), bookSlug)
                .filter(Book::isPublished)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        Map<String, Long> availability = availability(library.getId(), List.of(book.getId()));
        Map<String, Long> totals = totals(library.getId(), List.of(book.getId()));
        String categoryName = book.getCategoryId() == null ? null
                : categories.findById(book.getCategoryId()).map(Category::getName).orElse(null);
        long interested = interests.countByTenantIdAndBookId(library.getId(), book.getId());
        return PublicBookDto.of(book, library, categoryName,
                totals.getOrDefault(book.getId(), 0L), availability.getOrDefault(book.getId(), 0L),
                interested, publicBaseUrl);
    }

    /** A library is publicly browsable only when ACTIVE and directory-visible. */
    private Library publicLibrary(String slug) {
        return libraries.findBySlug(slug)
                .filter(l -> l.getStatus() == LibraryStatus.ACTIVE)
                .filter(Library::isVisibility)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
    }

    private Map<String, Long> availability(String tenantId, List<String> bookIds) {
        return statusCounts(tenantId, bookIds, CopyStatus.AVAILABLE);
    }

    private Map<String, Long> totals(String tenantId, List<String> bookIds) {
        Map<String, Long> result = new java.util.HashMap<>();
        for (Object[] row : copies.countByBookGrouped(tenantId, bookIds)) {
            if ((CopyStatus) row[1] != CopyStatus.REMOVED) {
                result.merge((String) row[0], (Long) row[2], Long::sum);
            }
        }
        return result;
    }

    private Map<String, Long> statusCounts(String tenantId, List<String> bookIds, CopyStatus status) {
        Map<String, Long> result = new java.util.HashMap<>();
        if (!bookIds.isEmpty()) {
            for (Object[] row : copies.countByBookGrouped(tenantId, bookIds)) {
                if ((CopyStatus) row[1] == status) {
                    result.merge((String) row[0], (Long) row[2], Long::sum);
                }
            }
        }
        return result;
    }

    private static String escape(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
