package com.libranet.catalog.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.service.EntitlementService;
import com.libranet.catalog.dto.BookAvailability;
import com.libranet.catalog.dto.BookDto;
import com.libranet.catalog.dto.BookSummaryDto;
import com.libranet.catalog.dto.CopyDto;
import com.libranet.catalog.dto.CopyRequests.CreateCopyRequest;
import com.libranet.catalog.dto.CopyRequests.UpdateCopyRequest;
import com.libranet.catalog.dto.CopyRequests.UpdateCopyStatusRequest;
import com.libranet.catalog.dto.UpsertBookRequest;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.model.BookCopy;
import com.libranet.catalog.model.Branch;
import com.libranet.catalog.model.Category;
import com.libranet.catalog.model.CopyStatus;
import com.libranet.catalog.model.ShelfLocation;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.catalog.repo.BranchRepository;
import com.libranet.catalog.repo.CategoryRepository;
import com.libranet.catalog.repo.ShelfLocationRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.CodeGenerator;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog: books + physical copies.
 * Reads: any APPROVED member of the tenant, but patrons only see published titles.
 * Writes: LIBRARY_ADMIN / LIBRARIAN (staff). Every mutation is audited.
 */
@Service
public class CatalogService {

    private final BookRepository books;
    private final BookCopyRepository copies;
    private final CategoryRepository categories;
    private final BranchRepository branches;
    private final ShelfLocationRepository shelfLocations;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final com.libranet.ai.service.EmbeddingService embeddingService;
    private final com.libranet.ai.service.SearchEventService searchEvents;

    public CatalogService(BookRepository books, BookCopyRepository copies, CategoryRepository categories,
                          BranchRepository branches, ShelfLocationRepository shelfLocations,
                          EntitlementService entitlements, AuditService audit,
                          com.libranet.ai.service.EmbeddingService embeddingService,
                          com.libranet.ai.service.SearchEventService searchEvents) {
        this.books = books;
        this.copies = copies;
        this.categories = categories;
        this.branches = branches;
        this.shelfLocations = shelfLocations;
        this.entitlements = entitlements;
        this.audit = audit;
        this.embeddingService = embeddingService;
        this.searchEvents = searchEvents;
    }

    // ---------- search / detail (members) ----------

    @Transactional(readOnly = true)
    public Page<BookSummaryDto> search(String q, String categoryId, String author, String publisher,
                                       String isbn, String language, Boolean includeUnpublished,
                                       Boolean availableOnly, Pageable pageable) {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());

        Specification<Book> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            if (!(staff && Boolean.TRUE.equals(includeUnpublished))) {
                ps.add(cb.isTrue(root.get("published")));
            }
            if (q != null && !q.isBlank()) {
                String like = like(q);
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("subtitle")), like),
                        cb.like(cb.lower(root.get("author")), like),
                        cb.like(cb.lower(root.get("coAuthors")), like),
                        cb.like(cb.lower(root.get("subjects")), like),
                        cb.like(cb.lower(root.get("isbn")), like)));
            }
            if (categoryId != null && !categoryId.isBlank()) {
                // category + all descendants so browsing a parent shows the whole subtree
                ps.add(root.get("categoryId").in(categorySubtree(tenantId, categoryId)));
            }
            if (author != null && !author.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("author")), like(author)));
            }
            if (publisher != null && !publisher.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("publisher")), like(publisher)));
            }
            if (isbn != null && !isbn.isBlank()) {
                ps.add(cb.equal(root.get("isbn"), normalizeIsbn(isbn)));
            }
            if (language != null && !language.isBlank()) {
                ps.add(cb.equal(cb.lower(root.get("language")), language.trim().toLowerCase(Locale.ROOT)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };

        Page<Book> page = books.findAll(spec, pageable);
        // demand signal (Phase 10): only member text searches are counted, never filtered browses
        if (q != null && !q.isBlank()) {
            searchEvents.record(tenantId, q, (int) page.getTotalElements(),
                    com.libranet.common.web.SecurityUsers.current().id());
        }
        if (page.isEmpty()) {
            return page.map(b -> BookSummaryDto.of(b, null, BookAvailability.NONE));
        }
        Map<String, BookAvailability> availability = availability(tenantId,
                page.getContent().stream().map(Book::getId).toList());
        Map<String, String> categoryNames = categoryNames(tenantId);
        return page.map(b -> BookSummaryDto.of(b, categoryNames.get(b.getCategoryId()),
                availability.getOrDefault(b.getId(), BookAvailability.NONE)));
    }

    @Transactional(readOnly = true)
    public BookDto detail(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        Book book = findScoped(bookId);
        assertVisible(book, ctx);
        return BookDto.of(book, categoryNames(ctx.libraryId()).get(book.getCategoryId()),
                availability(ctx.libraryId(), book.getId()));
    }

    // ---------- book CRUD (staff) ----------

    @Transactional
    public BookDto create(UpsertBookRequest req) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String tenantId = ctx.libraryId();
        entitlements.requireBookCapacity(tenantId, () -> books.countByTenantId(tenantId));
        requireCategoryBelongsToTenant(tenantId, req.categoryId());

        String normalized = normalizeIsbn(req.isbn());
        if (normalized != null && books.findByTenantIdAndIsbn(tenantId, normalized).isPresent()) {
            throw isbnConflict();
        }
        Book book = new Book();
        book.setTenantId(tenantId);
        apply(book, req);
        book.setSlug(uniqueSlug(tenantId, req.title(), null));
        try {
            books.saveAndFlush(book); // flush now: constraint errors surface here, not at commit
        } catch (DataIntegrityViolationException e) {
            throw isbnConflict();
        }
        audit.log(AuditAction.BOOK_CREATED, "Book", book.getId(),
                Map.of("title", book.getTitle(), "isbn", book.getIsbn() == null ? "" : book.getIsbn()));
        embeddingService.refreshBook(tenantId, book.getId()); // semantic index stays warm
        return detail(book.getId());
    }

    @Transactional
    public BookDto update(String bookId, UpsertBookRequest req) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String tenantId = ctx.libraryId();
        Book book = findScoped(bookId);
        requireCategoryBelongsToTenant(tenantId, req.categoryId());

        String normalized = normalizeIsbn(req.isbn());
        if (normalized != null && !normalized.equals(book.getIsbn())
                && books.findByTenantIdAndIsbn(tenantId, normalized).isPresent()) {
            throw isbnConflict();
        }
        apply(book, req);
        if (normalized == null || book.getSlug() == null || book.getSlug().isBlank()) {
            book.setSlug(uniqueSlug(tenantId, book.getTitle(), book.getId()));
        }
        try {
            books.saveAndFlush(book);
        } catch (DataIntegrityViolationException e) {
            throw isbnConflict();
        }
        audit.log(AuditAction.BOOK_UPDATED, "Book", book.getId(), Map.of("title", book.getTitle()));
        embeddingService.refreshBook(tenantId, book.getId());
        return detail(book.getId());
    }

    @Transactional
    public void delete(String bookId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        Book book = findScoped(bookId);
        long liveCopies = copies.countByTenantIdAndBookId(book.getTenantId(), bookId);
        if (liveCopies > 0) {
            throw ApiException.conflict("BOOK_HAS_COPIES",
                    "Remove or withdraw all copies before deleting this title (" + liveCopies + " exist)");
        }
        books.delete(book);
        embeddingService.removeBook(book.getTenantId(), bookId); // derived data — removed with the book
        audit.log(AuditAction.BOOK_DELETED, "Book", bookId, Map.of("title", book.getTitle()));
    }

    // ---------- copies (staff) ----------

    @Transactional(readOnly = true)
    public List<CopyDto> listCopies(String bookId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        Book book = findScoped(bookId);
        Map<String, Branch> branchById = branches.findByTenantIdOrderByMainDescNameAsc(book.getTenantId())
                .stream().collect(Collectors.toMap(Branch::getId, Function.identity()));
        Map<String, ShelfLocation> shelfById = shelfLocations
                .findByTenantIdOrderByBranchIdAscBuildingAscFloorAscShelfAsc(book.getTenantId())
                .stream().collect(Collectors.toMap(ShelfLocation::getId, Function.identity()));
        return copies.findByTenantIdAndBookIdOrderByBarcodeAsc(book.getTenantId(), bookId).stream()
                .map(c -> toDto(c, branchById, shelfById))
                .toList();
    }

    @Transactional
    public CopyDto addCopy(String bookId, CreateCopyRequest req) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String tenantId = ctx.libraryId();
        Book book = findScoped(bookId);
        requireBranchBelongsToTenant(tenantId, req.branchId());
        requireShelfBelongsToTenant(tenantId, req.shelfLocationId());

        BookCopy copy = new BookCopy();
        copy.setTenantId(tenantId);
        copy.setBookId(book.getId());
        copy.setBarcode(resolveBarcode(tenantId, req));
        copy.setAccessionNumber(blankToNull(req.accessionNumber()));
        copy.setBranchId(blankToNull(req.branchId()));
        copy.setShelfLocationId(blankToNull(req.shelfLocationId()));
        copy.setNotes(blankToNull(req.notes()));
        copy.setStatus(CopyStatus.AVAILABLE);
        try {
            copies.saveAndFlush(copy);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "COPY_IDENTIFIER_TAKEN",
                    "A copy with this barcode or accession number already exists in this library");
        }
        audit.log(AuditAction.COPY_CREATED, "BookCopy", copy.getId(),
                Map.of("bookId", book.getId(), "barcode", copy.getBarcode()));
        return getCopy(copy.getId());
    }

    @Transactional(readOnly = true)
    public CopyDto getCopy(String copyId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        BookCopy copy = findScopedCopy(copyId);
        Book book = findScoped(copy.getBookId());
        return toDto(copy, book.getTenantId());
    }

    @Transactional
    public CopyDto updateCopy(String copyId, UpdateCopyRequest req) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        BookCopy copy = findScopedCopy(copyId);
        String tenantId = copy.getTenantId();
        requireBranchBelongsToTenant(tenantId, req.branchId());
        requireShelfBelongsToTenant(tenantId, req.shelfLocationId());
        String barcode = req.barcode().trim();
        copies.findByTenantIdAndBarcode(tenantId, barcode)
                .filter(existing -> !existing.getId().equals(copyId))
                .ifPresent(existing -> { throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "BARCODE_TAKEN", "Another copy already uses this barcode"); });
        copy.setBarcode(barcode);
        copy.setAccessionNumber(blankToNull(req.accessionNumber()));
        copy.setBranchId(blankToNull(req.branchId()));
        copy.setShelfLocationId(blankToNull(req.shelfLocationId()));
        copy.setNotes(blankToNull(req.notes()));
        copies.save(copy);
        audit.log(AuditAction.COPY_UPDATED, "BookCopy", copyId, Map.of("barcode", copy.getBarcode()));
        return getCopy(copyId);
    }

    @Transactional
    public CopyDto changeCopyStatus(String copyId, UpdateCopyStatusRequest req) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        BookCopy copy = findScopedCopy(copyId);
        CopyStatus next = parseStatus(req.status());
        // BORROWED/RESERVED are owned by circulation flows (Phase 5) — staff can't set them directly.
        if (next == CopyStatus.BORROWED || next == CopyStatus.RESERVED) {
            throw ApiException.badRequest("STATUS_VIA_CIRCULATION",
                    next + " is managed by borrow/reserve flows, not manual edits");
        }
        if (copy.getStatus() == CopyStatus.BORROWED) {
            throw ApiException.conflict("COPY_IS_BORROWED",
                    "This copy is on loan; it must be returned before its status can change");
        }
        CopyStatus previous = copy.getStatus();
        copy.setStatus(next);
        if (req.notes() != null) {
            copy.setNotes(blankToNull(req.notes()));
        }
        copies.save(copy);
        audit.log(AuditAction.COPY_STATUS_CHANGED, "BookCopy", copyId,
                Map.of("from", previous.name(), "to", next.name()));
        return getCopy(copyId);
    }

    @Transactional
    public void deleteCopy(String copyId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        BookCopy copy = findScopedCopy(copyId);
        if (copy.getStatus() == CopyStatus.BORROWED) {
            throw ApiException.conflict("COPY_IS_BORROWED", "A copy on loan cannot be removed");
        }
        copies.delete(copy);
        audit.log(AuditAction.COPY_DELETED, "BookCopy", copyId, Map.of("barcode", copy.getBarcode()));
    }

    // ---------- internals ----------

    private Book findScoped(String bookId) {
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        return book;
    }

    private BookCopy findScopedCopy(String copyId) {
        BookCopy copy = copies.findById(copyId)
                .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "Copy not found"));
        TenantGuard.requireBelongsToCurrentTenant(copy.getTenantId());
        return copy;
    }

    private void assertVisible(Book book, TenantContext.Current ctx) {
        if (!book.isPublished()
                && !ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name())) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found"); // 404 — don't leak unpublished titles
        }
    }

    private void apply(Book book, UpsertBookRequest req) {
        book.setTitle(req.title().trim());
        book.setSubtitle(blankToNull(req.subtitle()));
        book.setAuthor(req.author().trim());
        book.setCoAuthors(blankToNull(req.coAuthors()));
        book.setIsbn(normalizeIsbn(req.isbn()));
        book.setPublisher(blankToNull(req.publisher()));
        book.setPublishedDate(blankToNull(req.publishedDate()));
        book.setEdition(blankToNull(req.edition()));
        book.setLanguage(blankToNull(req.language()));
        book.setPageCount(req.pageCount());
        book.setDescription(blankToNull(req.description()));
        book.setSubjects(blankToNull(req.subjects()));
        book.setCategoryId(blankToNull(req.categoryId()));
        book.setCoverRef(blankToNull(req.coverRef()));
        if (req.published() != null) {
            book.setPublished(req.published());
        }
        book.setAuthorUserId(blankToNull(req.authorUserId()));
        book.setSalePriceMinor(req.salePriceMinor());
        book.setContactNote(blankToNull(req.contactNote()));
    }

    private String uniqueSlug(String tenantId, String title, String excludeBookId) {
        String base = CodeGenerator.slugify(title);
        String candidate = base + "-" + CodeGenerator.randomToken(4).toLowerCase(Locale.ROOT);
        while (books.findByTenantIdAndSlug(tenantId, candidate)
                .filter(b -> !b.getId().equals(excludeBookId)).isPresent()) {
            candidate = base + "-" + CodeGenerator.randomToken(4).toLowerCase(Locale.ROOT);
        }
        return candidate;
    }

    static String normalizeIsbn(String isbn) {
        if (isbn == null) {
            return null;
        }
        String n = isbn.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        if (n.isEmpty()) {
            return null;
        }
        if (!n.matches("\\d{9}[\\dX]|\\d{13}")) {
            throw ApiException.badRequest("INVALID_ISBN", "ISBN must be 10 or 13 digits (hyphens allowed)");
        }
        return n;
    }

    /** Escapes LIKE wildcards in user input; the criteria query uses ESCAPE '\'. */
    private static String like(String raw) {
        String e = raw.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + e + "%";
    }

    private List<String> categorySubtree(String tenantId, String rootId) {
        Map<String, Category> byId = categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId)
                .stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        if (!byId.containsKey(rootId)) {
            throw ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found");
        }
        List<String> ids = new ArrayList<>();
        List<String> frontier = List.of(rootId);
        while (!frontier.isEmpty()) {
            ids.addAll(frontier);
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Category c : byId.values()) {
                    if (id.equals(c.getParentId())) {
                        next.add(c.getId());
                    }
                }
            }
            frontier = next;
        }
        return ids;
    }

    private Map<String, BookAvailability> availability(String tenantId, List<String> bookIds) {
        Map<String, BookAvailability> totals = new LinkedHashMap<>();
        Map<String, Long> available = new LinkedHashMap<>();
        for (Object[] row : copies.countByBookGrouped(tenantId, bookIds)) {
            String id = (String) row[0];
            CopyStatus status = (CopyStatus) row[1];
            long n = (Long) row[2];
            if (status != CopyStatus.REMOVED) {
                totals.merge(id, new BookAvailability(n, 0), (a, b) -> new BookAvailability(a.total() + n, a.available()));
                if (status == CopyStatus.AVAILABLE) {
                    available.merge(id, n, Long::sum);
                }
            }
        }
        Map<String, BookAvailability> result = new LinkedHashMap<>();
        totals.forEach((id, t) -> result.put(id, new BookAvailability(t.total(), available.getOrDefault(id, 0L))));
        return result;
    }

    private BookAvailability availability(String tenantId, String bookId) {
        return availability(tenantId, List.of(bookId)).getOrDefault(bookId, BookAvailability.NONE);
    }

    private Map<String, String> categoryNames(String tenantId) {
        return categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private CopyDto toDto(BookCopy c, String tenantId) {
        Branch branch = c.getBranchId() == null ? null : branches.findById(c.getBranchId())
                .filter(b -> tenantId.equals(b.getTenantId())).orElse(null);
        ShelfLocation shelf = c.getShelfLocationId() == null ? null
                : shelfLocations.findById(c.getShelfLocationId())
                        .filter(s -> tenantId.equals(s.getTenantId())).orElse(null);
        return CopyDto.of(c, branch == null ? null : branch.getName(), shelf == null ? null : shelf.display());
    }

    private CopyDto toDto(BookCopy c, Map<String, Branch> branches, Map<String, ShelfLocation> shelves) {
        Branch b = c.getBranchId() == null ? null : branches.get(c.getBranchId());
        ShelfLocation s = c.getShelfLocationId() == null ? null : shelves.get(c.getShelfLocationId());
        return CopyDto.of(c, b == null ? null : b.getName(), s == null ? null : s.display());
    }

    private String resolveBarcode(String tenantId, CreateCopyRequest req) {
        if (req.barcode() != null && !req.barcode().isBlank()) {
            String barcode = req.barcode().trim();
            copies.findByTenantIdAndBarcode(tenantId, barcode).ifPresent(existing -> {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "BARCODE_TAKEN",
                        "Another copy already uses this barcode");
            });
            return barcode;
        }
        Branch branch = resolveBarcodeBranch(tenantId, req.branchId());
        String prefix = branch != null && branch.getCode() != null && !branch.getCode().isBlank()
                ? branch.getCode().trim().toUpperCase(Locale.ROOT) : "CPY";
        return nextSequenceBarcode(tenantId, prefix);
    }

    private Branch resolveBarcodeBranch(String tenantId, String branchId) {
        if (branchId != null && !branchId.isBlank()) {
            return branches.findById(branchId).filter(b -> tenantId.equals(b.getTenantId())).orElse(null);
        }
        return branches.findFirstByTenantIdAndMainTrue(tenantId).orElse(null);
    }

    /** Scans existing barcodes with this prefix and continues the sequence. */
    private String nextSequenceBarcode(String tenantId, String prefix) {
        long max = copies.findByTenantIdAndBarcodeStartingWith(tenantId, prefix + "-").stream()
                .map(BookCopy::getBarcode)
                .map(b -> b.substring(prefix.length() + 1))
                .filter(s -> s.matches("\\d+"))
                .mapToLong(Long::parseLong)
                .max().orElse(0);
        return prefix + "-%06d".formatted(max + 1);
    }

    private void requireCategoryBelongsToTenant(String tenantId, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
        TenantGuard.requireBelongsToCurrentTenant(category.getTenantId());
    }

    private void requireBranchBelongsToTenant(String tenantId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return;
        }
        Branch branch = branches.findById(branchId)
                .orElseThrow(() -> ApiException.notFound("BRANCH_NOT_FOUND", "Branch not found"));
        TenantGuard.requireBelongsToCurrentTenant(branch.getTenantId());
    }

    private void requireShelfBelongsToTenant(String tenantId, String shelfLocationId) {
        if (shelfLocationId == null || shelfLocationId.isBlank()) {
            return;
        }
        ShelfLocation shelf = shelfLocations.findById(shelfLocationId)
                .orElseThrow(() -> ApiException.notFound("SHELF_LOCATION_NOT_FOUND", "Shelf location not found"));
        TenantGuard.requireBelongsToCurrentTenant(shelf.getTenantId());
    }

    private CopyStatus parseStatus(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("INVALID_STATUS", "Status is required");
        }
        try {
            return CopyStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "Status must be one of AVAILABLE, LOST, DAMAGED, MAINTENANCE, REMOVED (BORROWED/RESERVED are set by circulation)");
        }
    }

    private ApiException isbnConflict() {
        return ApiException.conflict("ISBN_TAKEN", "A book with this ISBN already exists in this library");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
