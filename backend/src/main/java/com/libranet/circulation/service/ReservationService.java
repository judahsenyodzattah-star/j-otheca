package com.libranet.circulation.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.service.EntitlementService;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.model.BookCopy;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.circulation.dto.LoanDtos.LoanDto;
import com.libranet.circulation.dto.ReservationDtos.ReservationDto;
import com.libranet.circulation.model.Loan;
import com.libranet.circulation.model.LoanPolicy;
import com.libranet.circulation.model.Reservation;
import com.libranet.circulation.repo.ReservationRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.repo.MembershipRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * Reservation lifecycle (spec §21):
 * place (RESERVED) → promotion assigns a copy (WAITING_FOR_PICKUP) → staff pulls it
 * (READY_FOR_PICKUP) → member collects at the desk (COLLECTED → loan created, copy BORROWED).
 * Side exits: CANCELLED and EXPIRED (pickup deadline missed, lazy-swept).
 */
@Service
public class ReservationService {

    private static final EnumSet<Reservation.Status> IN_FLIGHT = EnumSet.of(
            Reservation.Status.RESERVED, Reservation.Status.WAITING_FOR_PICKUP,
            Reservation.Status.READY_FOR_PICKUP);

    private final ReservationRepository reservations;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final CirculationService circulation;
    private final EntitlementService entitlements;
    private final NotificationService notifications;
    private final AuditService audit;

        private final PermissionService permissions;

    public ReservationService(ReservationRepository reservations, BookRepository books,
                              BookCopyRepository copies, MembershipRepository memberships,
                              UserRepository users, CirculationService circulation,
                              EntitlementService entitlements, NotificationService notifications,
                              AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.reservations = reservations;
        this.books = books;
        this.copies = copies;
        this.memberships = memberships;
        this.users = users;
        this.circulation = circulation;
        this.entitlements = entitlements;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------- member actions ----------

    @Transactional
    public ReservationDto place(String bookId) {
        TenantContext.Current ctx = TenantContext.require();
        entitlements.requireReservationsEnabled(ctx.libraryId());

        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        if (!book.isPublished()) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found"); // unpublished = invisible
        }

        Membership member = memberships.findById(ctx.membershipId())
                .orElseThrow(() -> ApiException.forbidden("NO_MEMBERSHIP", "Membership context broken"));
        circulation.requireBorrowable(member);

        if (reservations.existsByTenantIdAndBookIdAndMembershipIdAndStatusIn(
                ctx.libraryId(), bookId, member.getId(), List.copyOf(IN_FLIGHT))) {
            throw ApiException.conflict("ALREADY_RESERVED", "You already have an active reservation on this title");
        }
        LoanPolicy policy = circulation.policyFor(ctx.libraryId());
        long inFlight = reservations.countByTenantIdAndMembershipIdAndStatusIn(
                ctx.libraryId(), member.getId(), List.copyOf(IN_FLIGHT));
        if (inFlight >= policy.getMaxReservations()) {
            throw ApiException.conflict("RESERVATION_LIMIT_REACHED",
                    "Policy allows at most " + policy.getMaxReservations() + " active reservation(s)");
        }

        Reservation r = new Reservation();
        r.setTenantId(ctx.libraryId());
        r.setBookId(bookId);
        r.setMembershipId(member.getId());
        r.setUserId(member.getUserId());
        reservations.save(r);
        audit.log(AuditAction.RESERVATION_PLACED, "Reservation", r.getId(),
                Map.of("bookTitle", book.getTitle()));
        circulation.promoteNextReservation(bookId); // an available shelf copy short-circuits the queue
        return decorate(reservations.findById(r.getId()).orElseThrow());
    }

    @Transactional
    public ReservationDto cancel(String reservationId, String reason) {
        TenantContext.Current ctx = TenantContext.require();
        Reservation r = findScoped(reservationId);
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        if (!staff && !r.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("RESERVATION_NOT_FOUND", "Reservation not found");
        }
        if (!r.isInFlight()) {
            throw ApiException.conflict("RESERVATION_CLOSED", "This reservation is already " + r.getStatus());
        }
        r.setStatus(Reservation.Status.CANCELLED);
        r.setCancelledReason(reason == null || reason.isBlank()
                ? (staff ? "cancelled by staff" : "cancelled by member") : reason.trim());
        reservations.save(r);
        audit.log(AuditAction.RESERVATION_CANCELLED, "Reservation", r.getId(),
                Map.of("byStaff", staff));
        circulation.releaseReservedCopy(r);
        return decorate(r);
    }

    // ---------- staff desk actions ----------

    /**
     * Holds are desk work: staff need CIRCULATION_OPS (audit P4·E19), but a member is always
     * allowed to act on their OWN in-flight reservation (self-service hold management).
     */
    private TenantContext.Current requireDeskOrOwner(String reservationId) {
        TenantContext.Current ctx = TenantContext.require();
        if (ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name())) {
            return permissions.require(LibraryPerm.CIRCULATION_OPS); // may throw PERMISSION_DENIED
        }
        Reservation r = findScoped(reservationId);
        if (!r.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("RESERVATION_NOT_FOUND", "Reservation not found"); // invisibility
        }
        return ctx;
    }

    @Transactional
    public ReservationDto markReady(String reservationId) {
        TenantContext.Current ctx = requireDeskOrOwner(reservationId);
        circulation.expireOverdueReservations();
        Reservation r = findScoped(reservationId);
        if (r.getStatus() != Reservation.Status.WAITING_FOR_PICKUP) {
            throw ApiException.conflict("NOT_WAITING", "Only a reservation being prepared can be marked ready");
        }
        r.setStatus(Reservation.Status.READY_FOR_PICKUP);
        r.setReadyAt(Instant.now());
        reservations.save(r);
        audit.log(AuditAction.RESERVATION_READY, "Reservation", r.getId(), Map.of());
        books.findById(r.getBookId()).ifPresent(b -> notifications.notify(ctx.libraryId(), r.getUserId(),
                "RESERVATION_READY", "Ready for pickup: " + b.getTitle(),
                "\"" + b.getTitle() + "\" is waiting for you at the front desk until "
                        + r.getPickupDeadlineAt().toString().substring(0, 10) + "."));
        return decorate(r);
    }

    /** Desk collection: converts the hold into a real loan (RESERVED shelf copy becomes BORROWED). */
    @Transactional
    public LoanDto collect(String reservationId) {
        TenantContext.Current ctx = requireDeskOrOwner(reservationId);
        circulation.expireOverdueReservations();
        Reservation r = findScoped(reservationId);
        if (r.getStatus() == Reservation.Status.WAITING_FOR_PICKUP) {
            throw ApiException.conflict("NOT_READY",
                    "This reservation is still being prepared — mark it ready for pickup first");
        }
        if (r.getStatus() != Reservation.Status.READY_FOR_PICKUP) {
            throw ApiException.conflict("NOT_READY", "Only a ready reservation can be collected");
        }
        Membership member = memberships.findById(r.getMembershipId())
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
        circulation.requireBorrowable(member);
        BookCopy copy = copies.findById(r.getCopyId())
                .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "Assigned copy not found"));
        TenantGuard.requireBelongsToCurrentTenant(copy.getTenantId());

        Loan loan = circulation.createLoan(ctx.libraryId(), copy, member,
                SecurityUsers.current().id(), circulation.policyFor(ctx.libraryId()));
        r.setStatus(Reservation.Status.COLLECTED);
        r.setCollectedAt(Instant.now());
        r.setLoanId(loan.getId());
        reservations.save(r);
        audit.log(AuditAction.RESERVATION_COLLECTED, "Reservation", r.getId(),
                Map.of("loanId", loan.getId()));
        return circulation.toDto(loan, Instant.now());
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public List<ReservationDto> mine() {
        TenantContext.Current ctx = TenantContext.require();
        return reservations.findByTenantIdAndMembershipIdOrderByCreatedAtDesc(ctx.libraryId(), ctx.membershipId())
                .stream().map(this::decorate).toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationDto> listForBook(String bookId) {
        permissions.require(LibraryPerm.REPORTS_VIEW);
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());
        return reservations.findByTenantIdAndBookIdAndStatusInOrderByCreatedAtAsc(
                        book.getTenantId(), bookId, List.copyOf(IN_FLIGHT))
                .stream().map(this::decorate).toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationDto> readyShelf() {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        return reservations.findByTenantIdAndStatusOrderByCreatedAtAsc(
                        ctx.libraryId(), Reservation.Status.READY_FOR_PICKUP)
                .stream().map(this::decorate).toList();
    }

    // ---------- internals ----------

    private Reservation findScoped(String reservationId) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "Reservation not found"));
        TenantGuard.requireBelongsToCurrentTenant(r.getTenantId());
        return r;
    }

    private ReservationDto decorate(Reservation r) {
        String title = books.findById(r.getBookId()).map(Book::getTitle).orElse(null);
        String barcode = r.getCopyId() == null ? null
                : copies.findById(r.getCopyId()).map(BookCopy::getBarcode).orElse(null);
        Membership member = memberships.findById(r.getMembershipId()).orElse(null);
        User user = member == null ? null : users.findById(member.getUserId()).orElse(null);
        long position = r.getStatus() == Reservation.Status.RESERVED
                ? reservations.findByTenantIdAndBookIdAndStatusInOrderByCreatedAtAsc(
                        r.getTenantId(), r.getBookId(), List.of(Reservation.Status.RESERVED))
                        .stream().map(Reservation::getId).toList().indexOf(r.getId()) + 1L
                : 0L;
        return ReservationDto.of(r, title, barcode,
                member == null ? null : member.getMembershipNumber(),
                user == null ? null : user.getFullName(), position);
    }
}
