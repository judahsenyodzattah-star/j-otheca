package com.jotheca.publishing.service;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.catalog.model.Book;
import com.jotheca.catalog.model.BookCopy;
import com.jotheca.catalog.model.CopyStatus;
import com.jotheca.catalog.repo.BookCopyRepository;
import com.jotheca.catalog.repo.BookRepository;
import com.jotheca.circulation.service.NotificationService;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.tenant.TenantGuard;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.model.User;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.membership.model.LibraryRole;
import com.jotheca.membership.model.Membership;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.publishing.dto.PublishingDtos.PurchaseRequestDto;
import com.jotheca.publishing.model.PurchaseRequest;
import com.jotheca.publishing.repo.PurchaseRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jotheca.permissions.model.LibraryPerm;
import com.jotheca.permissions.service.PermissionService;

/** Optional physical sales (spec §24): members request, staff hands over a copy at the desk. */
@Service
public class PurchaseService {

    private final PurchaseRequestRepository requests;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;

        private final PermissionService permissions;

    public PurchaseService(PurchaseRequestRepository requests, BookRepository books,
                           BookCopyRepository copies, MembershipRepository memberships,
                           UserRepository users, NotificationService notifications, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.requests = requests;
        this.books = books;
        this.copies = copies;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public PurchaseRequestDto request(String bookId, String note) {
        TenantContext.Current ctx = TenantContext.require();
        Book book = findBook(ctx, bookId, false);
        if (book.getSalePriceMinor() == null) {
            throw ApiException.conflict("NOT_FOR_SALE", "This title is not for sale at this library");
        }
        if (requests.existsByTenantIdAndBookIdAndMembershipIdAndStatus(
                ctx.libraryId(), bookId, ctx.membershipId(), PurchaseRequest.Status.PENDING)) {
            throw ApiException.conflict("ALREADY_REQUESTED", "You already have a pending purchase request for this title");
        }
        PurchaseRequest r = new PurchaseRequest();
        r.setTenantId(ctx.libraryId());
        r.setBookId(bookId);
        r.setMembershipId(ctx.membershipId());
        r.setUserId(SecurityUsers.current().id());
        r.setPriceMinor(book.getSalePriceMinor());
        r.setNote(note == null || note.isBlank() ? null : note.trim());
        requests.save(r);
        audit.log(AuditAction.PURCHASE_REQUESTED, "PurchaseRequest", r.getId(),
                Map.of("bookTitle", book.getTitle(), "priceMinor", r.getPriceMinor()));
        // author hears about a sale request on their own book
        if (book.getAuthorUserId() != null) {
            notifications.notify(ctx.libraryId(), book.getAuthorUserId(), "PURCHASE_REQUESTED",
                    "Purchase request: " + book.getTitle(),
                    "A reader requested a copy of \"" + book.getTitle() + "\".");
        }
        return decorate(r);
    }

    @Transactional
    public PurchaseRequestDto cancel(String requestId) {
        TenantContext.Current ctx = TenantContext.require();
        PurchaseRequest r = findScoped(requestId);
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        if (!staff && !r.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("REQUEST_NOT_FOUND", "Purchase request not found");
        }
        if (r.getStatus() != PurchaseRequest.Status.PENDING) {
            throw ApiException.conflict("REQUEST_CLOSED", "This request is already " + r.getStatus());
        }
        r.setStatus(PurchaseRequest.Status.CANCELLED);
        requests.save(r);
        audit.log(AuditAction.PURCHASE_CANCELLED, "PurchaseRequest", requestId, Map.of("byStaff", staff));
        return decorate(r);
    }

    /** Staff: hand over a copy and close the sale. The copy leaves circulation (REMOVED, kept for history). */
    @Transactional
    public PurchaseRequestDto fulfill(String requestId, String barcode) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        PurchaseRequest r = findScoped(requestId);
        if (r.getStatus() != PurchaseRequest.Status.PENDING) {
            throw ApiException.conflict("REQUEST_CLOSED", "This request is already " + r.getStatus());
        }
        BookCopy copy = null;
        List<BookCopy> bookCopies = copies.findByTenantIdAndBookIdOrderByBarcodeAsc(ctx.libraryId(), r.getBookId());
        if (barcode != null && !barcode.isBlank()) {
            copy = bookCopies.stream()
                    .filter(c -> c.getBarcode().equalsIgnoreCase(barcode.trim()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "No copy of this title with that barcode"));
        } else {
            copy = bookCopies.stream().filter(c -> c.getStatus() == CopyStatus.AVAILABLE).findFirst()
                    .orElseThrow(() -> ApiException.conflict("NO_AVAILABLE_COPY",
                            "No available copy of this title to sell"));
        }
        if (copy.getStatus() != CopyStatus.AVAILABLE) {
            throw ApiException.conflict("COPY_NOT_AVAILABLE", "Copy " + copy.getBarcode() + " is " + copy.getStatus());
        }
        copy.setStatus(CopyStatus.REMOVED); // sold copies are never deleted — the register must keep history
        copy.setNotes((copy.getNotes() == null ? "" : copy.getNotes() + " | ")
                + "sold (purchase request " + r.getId() + ")");
        copies.save(copy);

        r.setStatus(PurchaseRequest.Status.FULFILLED);
        r.setCopyId(copy.getId());
        r.setFulfilledAt(Instant.now());
        r.setFulfilledById(SecurityUsers.current().id());
        requests.save(r);
        audit.log(AuditAction.PURCHASE_FULFILLED, "PurchaseRequest", r.getId(),
                Map.of("barcode", copy.getBarcode(), "priceMinor", r.getPriceMinor()));
        notifications.notify(ctx.libraryId(), r.getUserId(), "PURCHASE_FULFILLED",
                "Your copy is ready", "Copy " + copy.getBarcode() + " was handed over. Enjoy the book!");
        return decorate(r);
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public List<PurchaseRequestDto> mine() {
        TenantContext.Current ctx = TenantContext.require();
        return requests.findByTenantIdAndMembershipIdOrderByCreatedAtDesc(ctx.libraryId(), ctx.membershipId())
                .stream().map(this::decorate).toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseRequestDto> pendingForStaff() {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        return requests.findByTenantIdAndStatusOrderByCreatedAtAsc(
                        ctx.libraryId(), PurchaseRequest.Status.PENDING)
                .stream().map(this::decorate).toList();
    }

    // ---------- internals ----------

    private PurchaseRequest findScoped(String requestId) {
        PurchaseRequest r = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "Purchase request not found"));
        TenantGuard.requireBelongsToCurrentTenant(r.getTenantId());
        return r;
    }

    private Book findBook(TenantContext.Current ctx, String bookId, boolean staffView) {
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        if (!book.isPublished() && !staffView) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found");
        }
        return book;
    }

    private PurchaseRequestDto decorate(PurchaseRequest r) {
        String title = books.findById(r.getBookId()).map(Book::getTitle).orElse(null);
        Membership member = memberships.findById(r.getMembershipId()).orElse(null);
        User user = member == null ? null : users.findById(member.getUserId()).orElse(null);
        String barcode = r.getCopyId() == null ? null
                : copies.findById(r.getCopyId()).map(BookCopy::getBarcode).orElse(null);
        return PurchaseRequestDto.of(r, title,
                member == null ? null : member.getMembershipNumber(),
                user == null ? null : user.getFullName(), barcode);
    }
}
