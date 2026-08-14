package com.libranet.circulation.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.model.BookCopy;
import com.libranet.catalog.model.CopyStatus;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.circulation.dto.LoanDtos.IssueLoanRequest;
import com.libranet.circulation.dto.LoanDtos.LoanDto;
import com.libranet.circulation.dto.LoanDtos.MemberLookupDto;
import com.libranet.circulation.dto.LoanDtos.ReturnLoanRequest;
import com.libranet.circulation.dto.PolicyDtos.PolicyDto;
import com.libranet.circulation.dto.PolicyDtos.UpdatePolicyRequest;
import com.libranet.circulation.model.Loan;
import com.libranet.circulation.model.LoanPolicy;
import com.libranet.circulation.model.Reservation;
import com.libranet.circulation.repo.LoanPolicyRepository;
import com.libranet.circulation.repo.LoanRepository;
import com.libranet.circulation.repo.ReservationRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * Desk circulation: issue / return / renew, member-card lookup, and the machinery that keeps the
 * reservation queue moving (promotion on return, lazy expiry sweep). Staff-only writes;
 * members read their own loans and renew within policy.
 */
@Service
public class CirculationService {

    private final LoanRepository loans;
    private final LoanPolicyRepository policies;
    private final ReservationRepository reservations;
    private final BookCopyRepository copies;
    private final BookRepository books;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final FineService fines;
    private final NotificationService notifications;
    private final AuditService audit;
    private final TransactionTemplate sweepTemplate;

        private final PermissionService permissions;

    public CirculationService(LoanRepository loans, LoanPolicyRepository policies,
                              ReservationRepository reservations, BookCopyRepository copies,
                              BookRepository books, MembershipRepository memberships, UserRepository users,
                              FineService fines, NotificationService notifications, AuditService audit,
                              PlatformTransactionManager txManager,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.loans = loans;
        this.policies = policies;
        this.reservations = reservations;
        this.copies = copies;
        this.books = books;
        this.memberships = memberships;
        this.users = users;
        this.fines = fines;
        this.notifications = notifications;
        this.audit = audit;
        this.sweepTemplate = new TransactionTemplate(txManager);
        this.sweepTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- policy ----------

    @Transactional(readOnly = true)
    public PolicyDto policy() {
        return PolicyDto.from(policyFor(TenantContext.requireLibraryId()));
    }

    @Transactional
    public PolicyDto updatePolicy(UpdatePolicyRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.POLICY_MANAGE);
        LoanPolicy p = policyFor(ctx.libraryId());
        if (req.loanDays() != null) p.setLoanDays(req.loanDays());
        if (req.renewDays() != null) p.setRenewDays(req.renewDays());
        if (req.maxActiveLoans() != null) p.setMaxActiveLoans(req.maxActiveLoans());
        if (req.maxRenewals() != null) p.setMaxRenewals(req.maxRenewals());
        if (req.maxReservations() != null) p.setMaxReservations(req.maxReservations());
        if (req.pickupDeadlineHours() != null) p.setPickupDeadlineHours(req.pickupDeadlineHours());
        if (req.graceDays() != null) p.setGraceDays(req.graceDays());
        if (req.finePerDayMinor() != null) p.setFinePerDayMinor(req.finePerDayMinor());
        if (req.replacementFeeMinor() != null) p.setReplacementFeeMinor(req.replacementFeeMinor());
        if (req.currency() != null) p.setCurrency(req.currency());
        policies.save(p);
        audit.log(AuditAction.CIRCULATION_POLICY_UPDATED, "LoanPolicy", p.getId(), Map.of());
        return PolicyDto.from(p);
    }

    LoanPolicy policyFor(String tenantId) {
        return policies.findByTenantId(tenantId).orElseGet(() -> {
            LoanPolicy p = new LoanPolicy();
            p.setTenantId(tenantId);
            return policies.save(p);
        });
    }

    // ---------- issue (desk checkout) ----------

    @Transactional
    public LoanDto issue(IssueLoanRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        Membership member = resolveMember(ctx.libraryId(), req.membershipId(), req.membershipNumber());
        requireBorrowable(member);
        BookCopy copy = resolveCopy(ctx.libraryId(), req.copyId(), req.barcode());
        if (copy.getStatus() == CopyStatus.BORROWED) {
            throw ApiException.conflict("COPY_NOT_AVAILABLE", "This copy is already on loan");
        }
        if (copy.getStatus() == CopyStatus.RESERVED) {
            throw ApiException.conflict("COPY_RESERVED",
                    "This copy is held for a reservation — collect it through the reservation flow");
        }
        if (copy.getStatus() != CopyStatus.AVAILABLE) {
            throw ApiException.conflict("COPY_NOT_AVAILABLE",
                    "Copy is " + copy.getStatus() + " and cannot be issued");
        }
        LoanPolicy policy = policyFor(ctx.libraryId());
        long active = loans.countByTenantIdAndMembershipIdAndStatus(
                ctx.libraryId(), member.getId(), Loan.Status.ACTIVE);
        if (active >= policy.getMaxActiveLoans()) {
            throw ApiException.conflict("LOAN_LIMIT_REACHED",
                    "Member already has " + active + " active loan(s); policy allows " + policy.getMaxActiveLoans());
        }
        Loan loan = createLoan(ctx.libraryId(), copy, member, SecurityUsers.current().id(), policy);
        return toDto(loan, Instant.now());
    }

    /** Shared by desk issue and reservation collection. Caller has already validated availability. */
    Loan createLoan(String tenantId, BookCopy copy, Membership member, String staffUserId, LoanPolicy policy) {
        Instant now = Instant.now();
        copy.setStatus(CopyStatus.BORROWED);
        copies.save(copy);
        Loan loan = new Loan();
        loan.setTenantId(tenantId);
        loan.setBookId(copy.getBookId());
        loan.setCopyId(copy.getId());
        loan.setMembershipId(member.getId());
        loan.setUserId(member.getUserId());
        loan.setIssuedById(staffUserId);
        loan.setIssuedAt(now);
        loan.setDueAt(now.plus(policy.getLoanDays(), ChronoUnit.DAYS));
        loans.save(loan);
        audit.log(AuditAction.LOAN_ISSUED, "Loan", loan.getId(),
                Map.of("copyBarcode", copy.getBarcode(), "membershipId", member.getId(), "dueAt", loan.getDueAt().toString()));
        books.findById(copy.getBookId()).ifPresent(b ->
                notifications.notify(tenantId, member.getUserId(), "LOAN_ISSUED",
                        "Loan issued: " + b.getTitle(),
                        "\"" + b.getTitle() + "\" (copy " + copy.getBarcode() + ") is due back on "
                                + loan.getDueAt().toString().substring(0, 10) + "."));
        return loan;
    }

    // ---------- return ----------

    @Transactional
    public LoanDto returnByCopy(ReturnLoanRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        BookCopy copy = resolveCopy(ctx.libraryId(), req.copyId(), req.barcode());
        Loan loan = loans.findByTenantIdAndCopyIdAndStatus(ctx.libraryId(), copy.getId(), Loan.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("LOAN_NOT_FOUND", "No active loan for this copy"));
        return closeLoan(loan, copy);
    }

    @Transactional
    public LoanDto returnByLoan(String loanId) {
        permissions.require(LibraryPerm.CIRCULATION_OPS);
        Loan loan = findScopedLoan(loanId);
        if (loan.getStatus() != Loan.Status.ACTIVE) {
            throw ApiException.conflict("LOAN_NOT_ACTIVE", "This loan is already closed");
        }
        BookCopy copy = findScopedCopy(loan.getCopyId());
        return closeLoan(loan, copy);
    }

    private LoanDto closeLoan(Loan loan, BookCopy copy) {
        Instant now = Instant.now();
        LoanPolicy policy = policyFor(loan.getTenantId());
        loan.setStatus(Loan.Status.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedById(SecurityUsers.current().id());
        loans.save(loan);

        copy.setStatus(CopyStatus.AVAILABLE);
        copies.save(copy);
        audit.log(AuditAction.LOAN_RETURNED, "Loan", loan.getId(), Map.of("copyBarcode", copy.getBarcode()));

        // late fine — grace window applies, fine disabled when per-day fee is 0
        if (now.isAfter(loan.getDueAt().plus(policy.getGraceDays(), ChronoUnit.DAYS))) {
            long daysLate = Math.max(1, ChronoUnit.DAYS.between(loan.getDueAt(), now) + 1);
            fines.assessLate(loan, policy, daysLate);
        }
        expireOverdueReservations();                    // keep the queue honest before promoting
        promoteNextReservation(loan.getBookId());       // returned copy may satisfy the queue head
        return toDto(loan, now);
    }

    // ---------- renew ----------

    @Transactional
    public LoanDto renew(String loanId) {
        TenantContext.Current ctx = TenantContext.require();
        Loan loan = findScopedLoan(loanId);
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        if (!staff && !loan.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("LOAN_NOT_FOUND", "Loan not found"); // others' loans hidden
        }
        if (loan.getStatus() != Loan.Status.ACTIVE) {
            throw ApiException.conflict("LOAN_NOT_ACTIVE", "Only active loans can be renewed");
        }
        LoanPolicy policy = policyFor(ctx.libraryId());
        if (loan.getRenewedCount() >= policy.getMaxRenewals()) {
            throw ApiException.conflict("RENEWAL_LIMIT_REACHED",
                    "This loan has already been renewed the maximum of " + policy.getMaxRenewals() + " time(s)");
        }
        boolean someoneWaiting = reservations.existsByTenantIdAndBookIdAndStatusIn(loan.getTenantId(),
                loan.getBookId(), List.of(
                        Reservation.Status.RESERVED, Reservation.Status.WAITING_FOR_PICKUP,
                        Reservation.Status.READY_FOR_PICKUP));
        if (someoneWaiting) {
            throw ApiException.conflict("RESERVATION_BLOCKS_RENEWAL",
                    "Another member is waiting for this title — it cannot be renewed");
        }
        loan.setDueAt(loan.getDueAt().plus(policy.getRenewDays(), ChronoUnit.DAYS));
        loan.setRenewedCount(loan.getRenewedCount() + 1);
        loans.save(loan);
        audit.log(AuditAction.LOAN_RENEWED, "Loan", loan.getId(),
                Map.of("renewedCount", loan.getRenewedCount(), "newDueAt", loan.getDueAt().toString()));
        return toDto(loan, Instant.now());
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public Page<LoanDto> list(String membershipId, boolean activeOnly, Pageable pageable) {
        TenantContext.Current ctx = TenantContext.require();
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String filterMembership = membershipId;
        if (!staff) {
            filterMembership = ctx.membershipId(); // patrons only ever see their own
        }
        Page<Loan> page = (filterMembership != null && !filterMembership.isBlank())
                ? loans.findByTenantIdAndMembershipIdOrderByIssuedAtDesc(ctx.libraryId(), filterMembership, pageable)
                : loans.findByTenantIdOrderByIssuedAtDesc(ctx.libraryId(), pageable);
        Instant now = Instant.now();
        return page.map(l -> toDto(l, now));
    }

    @Transactional(readOnly = true)
    public List<LoanDto> myActiveLoans() {
        TenantContext.Current ctx = TenantContext.require();
        Instant now = Instant.now();
        return loans.findByTenantIdAndMembershipIdAndStatus(
                        ctx.libraryId(), ctx.membershipId(), Loan.Status.ACTIVE)
                .stream().map(l -> toDto(l, now)).toList();
    }

    @Transactional(readOnly = true)
    public MemberLookupDto lookupMember(String membershipNumber) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CIRCULATION_OPS);
        Membership member = memberships.findByTenantIdAndMembershipNumber(ctx.libraryId(),
                        membershipNumber == null ? "" : membershipNumber.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "No member with this card number"));
        User user = users.findById(member.getUserId()).orElse(null);
        long activeLoans = loans.countByTenantIdAndMembershipIdAndStatus(
                ctx.libraryId(), member.getId(), Loan.Status.ACTIVE);
        long openFineTotal = fines.openBalanceMinor(ctx.libraryId(), member.getId());
        return new MemberLookupDto(member.getId(), member.getMembershipNumber(),
                user == null ? null : user.getFullName(), user == null ? null : user.getEmail(),
                member.getMembershipType().name(), member.getStatus().name(), activeLoans, openFineTotal);
    }

    // ---------- reservation queue machinery (used by ReservationService & web flows) ----------

    /** Moves the queued head to WAITING_FOR_PICKUP if an available copy exists. No-op otherwise. */
    @Transactional
    public void promoteNextReservation(String bookId) {
        String tenantId = TenantContext.requireLibraryId();
        var head = reservations.findFirstByTenantIdAndBookIdAndStatusOrderByCreatedAtAsc(
                tenantId, bookId, Reservation.Status.RESERVED);
        if (head.isEmpty()) {
            return;
        }
        var freeCopy = copies.findByTenantIdAndBookIdOrderByBarcodeAsc(tenantId, bookId).stream()
                .filter(c -> c.getStatus() == CopyStatus.AVAILABLE)
                .findFirst();
        if (freeCopy.isEmpty()) {
            return;
        }
        Reservation r = head.get();
        BookCopy copy = freeCopy.get();
        LoanPolicy policy = policyFor(tenantId);
        copy.setStatus(CopyStatus.RESERVED);
        copies.save(copy);
        r.setCopyId(copy.getId());
        r.setStatus(Reservation.Status.WAITING_FOR_PICKUP);
        r.setPickupDeadlineAt(Instant.now().plus(policy.getPickupDeadlineHours(), ChronoUnit.HOURS));
        reservations.save(r);
        books.findById(bookId).ifPresent(b -> notifications.notify(tenantId, r.getUserId(),
                "RESERVATION_PREPARING", "Reservation being prepared: " + b.getTitle(),
                "Copy " + copy.getBarcode() + " of \"" + b.getTitle()
                        + "\" has been set aside for you and is being prepared for pickup."));
    }

    /** Frees the held copy of an ending reservation and feeds the queue. */
    @Transactional
    public void releaseReservedCopy(Reservation r) {
        if (r.getCopyId() == null) {
            return;
        }
        copies.findById(r.getCopyId())
                .filter(c -> c.getStatus() == CopyStatus.RESERVED)
                .ifPresent(c -> {
                    c.setStatus(CopyStatus.AVAILABLE);
                    copies.save(c);
                    promoteNextReservation(r.getBookId());
                });
    }

    /**
     * Lazy sweep: expire in-flight reservations whose pickup window has closed. Runs in REQUIRES_NEW so
     * the expiry survives a caller that later throws (e.g. marking an already-expired hold as ready
     * answers 409, yet the expiry itself must still be committed).
     */
    public void expireOverdueReservations() {
        String tenantId = TenantContext.requireLibraryId();
        sweepTemplate.execute(status -> {
            List<Reservation> stale = reservations.findByTenantIdAndStatusInAndPickupDeadlineAtBefore(
                    tenantId,
                    List.of(Reservation.Status.WAITING_FOR_PICKUP, Reservation.Status.READY_FOR_PICKUP),
                    Instant.now());
            for (Reservation r : stale) {
                r.setStatus(Reservation.Status.EXPIRED);
                r.setCancelledReason("pickup deadline missed");
                reservations.save(r);
                audit.log(AuditAction.RESERVATION_EXPIRED, "Reservation", r.getId(), Map.of());
                releaseReservedCopy(r);
            }
            return null;
        });
    }

    // ---------- shared internals ----------

    Membership resolveMember(String tenantId, String membershipId, String membershipNumber) {
        Membership member = null;
        if (membershipId != null && !membershipId.isBlank()) {
            member = memberships.findById(membershipId.trim()).orElse(null);
        } else if (membershipNumber != null && !membershipNumber.isBlank()) {
            member = memberships.findByTenantIdAndMembershipNumber(tenantId,
                    membershipNumber.trim().toUpperCase()).orElse(null);
        }
        if (member == null) {
            throw ApiException.notFound("MEMBER_NOT_FOUND", "Member not found");
        }
        TenantGuard.requireBelongsToCurrentTenant(member.getTenantId());
        return member;
    }

    void requireBorrowable(Membership member) {
        if (member.getStatus() != MembershipStatus.APPROVED) {
            throw ApiException.conflict("MEMBERSHIP_NOT_APPROVED", "Only approved members can borrow");
        }
        if (member.getExpiresAt() != null && member.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("MEMBERSHIP_EXPIRED", "This membership has expired");
        }
    }

    BookCopy resolveCopy(String tenantId, String copyId, String barcode) {
        BookCopy copy;
        if (copyId != null && !copyId.isBlank()) {
            copy = copies.findById(copyId.trim())
                    .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "Copy not found"));
        } else if (barcode != null && !barcode.isBlank()) {
            copy = copies.findByTenantIdAndBarcode(tenantId, barcode.trim())
                    .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "Copy not found"));
        } else {
            throw ApiException.badRequest("COPY_REFERENCE_REQUIRED",
                    "Provide copyId or barcode (desk scan)");
        }
        TenantGuard.requireBelongsToCurrentTenant(copy.getTenantId());
        return copy;
    }

    Loan findScopedLoan(String loanId) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("LOAN_NOT_FOUND", "Loan not found"));
        TenantGuard.requireBelongsToCurrentTenant(loan.getTenantId());
        return loan;
    }

    private BookCopy findScopedCopy(String copyId) {
        BookCopy copy = copies.findById(copyId)
                .orElseThrow(() -> ApiException.notFound("COPY_NOT_FOUND", "Copy not found"));
        TenantGuard.requireBelongsToCurrentTenant(copy.getTenantId());
        return copy;
    }

    LoanDto toDto(Loan loan, Instant now) {
        Map<String, Book> bookById = books.findAllById(List.of(loan.getBookId())).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));
        String barcode = copies.findById(loan.getCopyId()).map(BookCopy::getBarcode).orElse(null);
        Membership member = memberships.findById(loan.getMembershipId()).orElse(null);
        User user = member == null ? null : users.findById(member.getUserId()).orElse(null);
        Book book = bookById.get(loan.getBookId());
        return LoanDto.of(loan, book == null ? null : book.getTitle(), barcode,
                member == null ? null : member.getMembershipNumber(),
                user == null ? null : user.getFullName(), now);
    }
}
