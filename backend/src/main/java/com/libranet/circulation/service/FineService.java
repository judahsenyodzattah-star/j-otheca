package com.libranet.circulation.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.CopyStatus;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.circulation.dto.FineDtos.AssessFineRequest;
import com.libranet.circulation.dto.FineDtos.FineDto;
import com.libranet.circulation.dto.FineDtos.SettleFineRequest;
import com.libranet.circulation.model.Fine;
import com.libranet.circulation.model.Loan;
import com.libranet.circulation.model.LoanPolicy;
import com.libranet.circulation.repo.FineRepository;
import com.libranet.circulation.repo.LoanPolicyRepository;
import com.libranet.circulation.repo.LoanRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/** Charge lifecycle: auto late-fees on return, staff assessments for lost/damaged, settle & waive. */
@Service
public class FineService {

    private final FineRepository fines;
    private final LoanRepository loans;
    private final LoanPolicyRepository policies;
    private final BookCopyRepository copies;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final com.libranet.billing.service.PaymentService payments;

        private final PermissionService permissions;

    public FineService(FineRepository fines, LoanRepository loans, LoanPolicyRepository policies,
                       BookCopyRepository copies, MembershipRepository memberships, UserRepository users,
                       NotificationService notifications, AuditService audit,
                       com.libranet.billing.service.PaymentService payments,
                        PermissionService permissions) {
        this.permissions = permissions;
        this.fines = fines;
        this.loans = loans;
        this.policies = policies;
        this.copies = copies;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.payments = payments;
    }

    /** Called by CirculationService on a late return. No-ops when the library doesn't fine. */
    @Transactional
    public Fine assessLate(Loan loan, LoanPolicy policy, long daysLate) {
        if (policy.getFinePerDayMinor() <= 0) {
            return null;
        }
        Fine fine = base(loan.getTenantId(), loan.getMembershipId(), loan.getUserId(), Fine.Type.LATE);
        fine.setLoanId(loan.getId());
        fine.setAmountMinor(daysLate * policy.getFinePerDayMinor());
        fine.setCurrency(policy.getCurrency());
        fine.setNote(daysLate + " day(s) overdue");
        fines.save(fine);
        audit.log(AuditAction.FINE_ASSESSED, "Fine", fine.getId(),
                Map.of("type", "LATE", "amountMinor", fine.getAmountMinor(), "loanId", loan.getId()));
        notifications.notify(loan.getTenantId(), loan.getUserId(), "FINE_ASSESSED",
                "Late return fine: " + formatGhsLike(fine),
                "Your loan returned " + daysLate + " day(s) late. Fine: " + formatGhsLike(fine) + ".");
        return fine;
    }

    private static String formatGhsLike(Fine fine) {
        return fine.getCurrency() + " " + String.format(Locale.ROOT, "%.2f", fine.getAmountMinor() / 100.0);
    }

    // ---------- staff assessment (lost / damaged / replacement / manual) ----------

    @Transactional
    public FineDto assess(AssessFineRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.FINES_MANAGE);
        Membership member = memberships.findById(req.membershipId())
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
        TenantGuard.requireBelongsToCurrentTenant(member.getTenantId());

        Fine.Type type = parseType(req.type());
        LoanPolicy policy = policies.findByTenantId(ctx.libraryId()).orElse(new LoanPolicy());
        long amount;
        if (req.amountMinor() != null) {
            amount = req.amountMinor();
        } else if (type == Fine.Type.LOST || type == Fine.Type.REPLACEMENT) {
            amount = policy.getReplacementFeeMinor();
        } else {
            throw ApiException.badRequest("AMOUNT_REQUIRED", "amountMinor is required for " + type + " fines");
        }

        Loan loan = null;
        if (req.loanId() != null && !req.loanId().isBlank()) {
            loan = loans.findById(req.loanId().trim())
                    .orElseThrow(() -> ApiException.notFound("LOAN_NOT_FOUND", "Loan not found"));
            TenantGuard.requireBelongsToCurrentTenant(loan.getTenantId());
            if (!loan.getMembershipId().equals(member.getId())) {
                throw ApiException.badRequest("LOAN_MEMBER_MISMATCH", "That loan does not belong to this member");
            }
        }

        Fine fine = base(ctx.libraryId(), member.getId(), member.getUserId(), type);
        fine.setLoanId(loan == null ? null : loan.getId());
        fine.setAmountMinor(amount);
        fine.setCurrency(policy.getCurrency() == null ? "GHS" : policy.getCurrency());
        fine.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        fines.save(fine);

        // lost/damaged flows close the loan and retire the copy from circulation
        if (loan != null && loan.getStatus() == Loan.Status.ACTIVE
                && (type == Fine.Type.LOST || type == Fine.Type.DAMAGED || type == Fine.Type.REPLACEMENT)) {
            loan.setStatus(Loan.Status.RETURNED);
            loan.setReturnedAt(Instant.now());
            loan.setReturnedById(SecurityUsers.current().id());
            loans.save(loan);
            copies.findById(loan.getCopyId()).ifPresent(copy -> {
                copy.setStatus(type == Fine.Type.DAMAGED ? CopyStatus.DAMAGED : CopyStatus.LOST);
                copies.save(copy);
            });
        }

        audit.log(AuditAction.FINE_ASSESSED, "Fine", fine.getId(),
                Map.of("type", type.name(), "amountMinor", amount));
        notifications.notify(ctx.libraryId(), member.getUserId(), "FINE_ASSESSED",
                "Fine assessed: " + formatGhsLike(fine),
                (fine.getNote() == null ? type.name() + " charge" : fine.getNote())
                        + " — " + formatGhsLike(fine) + ". Pay at the front desk.");
        return decorate(fine);
    }

    // ---------- settle / waive ----------

    @Transactional
    public FineDto settle(String fineId, SettleFineRequest req) {
        permissions.require(LibraryPerm.FINES_MANAGE);
        Fine fine = findScoped(fineId);
        if (fine.getStatus() != Fine.Status.OPEN) {
            throw ApiException.conflict("FINE_NOT_OPEN", "Only open fines can be settled");
        }
        if (req.amountMinor() > fine.getAmountMinor()) {
            throw ApiException.badRequest("OVERPAYMENT", "Amount exceeds the fine balance");
        }
        fine.setSettledAt(Instant.now());
        fine.setSettledById(SecurityUsers.current().id());
        String note = req.settlement().trim()
                + (req.amountMinor() < fine.getAmountMinor()
                        ? " (" + formatMinor(req.amountMinor(), fine.getCurrency()) + " of "
                            + formatMinor(fine.getAmountMinor(), fine.getCurrency()) + " paid; remainder waived)"
                        : "");
        fine.setSettlement(note);
        fine.setStatus(Fine.Status.PAID);
        fines.save(fine);
        audit.log(AuditAction.FINE_SETTLED, "Fine", fineId,
                Map.of("amountMinor", fine.getAmountMinor(), "settlement", req.settlement()));
        return decorate(fine);
    }

    @Transactional
    public FineDto waive(String fineId, String reason) {
        permissions.require(LibraryPerm.FINES_WAIVE); // waivers are an admin decision
        Fine fine = findScoped(fineId);
        if (fine.getStatus() != Fine.Status.OPEN) {
            throw ApiException.conflict("FINE_NOT_OPEN", "Only open fines can be waived");
        }
        fine.setStatus(Fine.Status.WAIVED);
        fine.setSettledAt(Instant.now());
        fine.setSettledById(SecurityUsers.current().id());
        fine.setSettlement("waived" + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        fines.save(fine);
        audit.log(AuditAction.FINE_WAIVED, "Fine", fineId, Map.of("amountMinor", fine.getAmountMinor()));
        return decorate(fine);
    }

    // ---------- patron online rail (Phase 9) ----------
    //
    // Desk settlements (cash/card at the front desk) stay staff-only above. The online rail lets
    // a patron pay their OWN open fine through the payment provider: pay-online starts a checkout,
    // confirm-online verifies with the provider server-side (never trusting the client) and then
    // settles. Other members' fines answer 404 — existence is never revealed.

    private Fine findOwned(String fineId) {
        TenantContext.Current ctx = TenantContext.require();
        Fine fine = findScoped(fineId);
        if (!fine.getMembershipId().equals(ctx.membershipId())) {
            throw ApiException.notFound("FINE_NOT_FOUND", "Fine not found");
        }
        return fine;
    }

    /** Patron: start a checkout for the full amount of one of their own OPEN fines. */
    @Transactional
    public com.libranet.billing.service.PaymentService.CheckoutDetails payOnline(String fineId) {
        TenantContext.Current ctx = TenantContext.require();
        Fine fine = findOwned(fineId);
        if (fine.getStatus() != Fine.Status.OPEN) {
            throw ApiException.conflict("FINE_NOT_OPEN", "Only open fines can be paid online");
        }
        User payer = users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
        return payments.initiateFinePayment(payer, ctx.libraryId(), fine.getId(),
                fine.getAmountMinor(), fine.getCurrency());
    }

    /**
     * Patron: after completing checkout, confirm via the provider reference. The payment is
     * verified server-side; only a PAID payment that belongs to THIS fine settles it.
     */
    @Transactional
    public FineDto confirmOnline(String fineId, String reference) {
        TenantContext.Current ctx = TenantContext.require();
        Fine fine = findOwned(fineId);
        if (fine.getStatus() != Fine.Status.OPEN) {
            throw ApiException.conflict("FINE_NOT_OPEN", "Only open fines can be paid online");
        }
        if (reference == null || reference.isBlank()) {
            throw ApiException.badRequest("REFERENCE_REQUIRED", "The provider payment reference is required");
        }
        com.libranet.billing.model.Payment payment = payments.confirmByReference(reference.trim());
        if (payment.getFineId() == null || !payment.getFineId().equals(fine.getId())
                || !ctx.libraryId().equals(payment.getTenantId())
                || payment.getAmountMinor() != fine.getAmountMinor()) {
            throw ApiException.badRequest("PAYMENT_MISMATCH", "That payment does not belong to this fine");
        }
        if (payment.getStatus() != com.libranet.billing.model.Payment.Status.PAID) {
            throw ApiException.badRequest("PAYMENT_NOT_COMPLETED",
                    "The provider has not completed this payment (status " + payment.getStatus() + ")");
        }
        fine.setStatus(Fine.Status.PAID);
        fine.setSettledAt(Instant.now());
        fine.setSettledById(SecurityUsers.current().id());
        fine.setSettlement("online (" + payment.getProvider().name().toLowerCase(Locale.ROOT)
                + " " + payment.getProviderReference() + ")");
        fines.save(fine);
        audit.log(AuditAction.FINE_SETTLED, "Fine", fine.getId(),
                Map.of("amountMinor", fine.getAmountMinor(), "method", "online",
                       "reference", payment.getProviderReference()));
        notifications.notify(ctx.libraryId(), fine.getUserId(), "FINE_PAID",
                "Fine paid: " + formatGhsLike(fine),
                "Your online payment of " + formatGhsLike(fine) + " was confirmed (reference "
                        + payment.getProviderReference() + "). Thank you!");
        return decorate(fine);
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public List<FineDto> list(String membershipId) {
        TenantContext.Current ctx = TenantContext.require();
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String scope = staff && membershipId != null && !membershipId.isBlank()
                ? membershipId.trim() : ctx.membershipId();  // patrons always see only their own
        return fines.findByTenantIdAndMembershipIdOrderByCreatedAtDesc(ctx.libraryId(), scope)
                .stream().map(this::decorate).toList();
    }

    @Transactional(readOnly = true)
    public List<FineDto> myOpen() {
        TenantContext.Current ctx = TenantContext.require();
        return fines.findByTenantIdAndMembershipIdAndStatus(ctx.libraryId(), ctx.membershipId(), Fine.Status.OPEN)
                .stream().map(this::decorate).toList();
    }

    /** Sum of OPEN fines in minor units — shown on the staff card-lookup and used by borrow rules later. */
    @Transactional(readOnly = true)
    public long openBalanceMinor(String tenantId, String membershipId) {
        return fines.findByTenantIdAndMembershipIdAndStatus(tenantId, membershipId, Fine.Status.OPEN)
                .stream().mapToLong(Fine::getAmountMinor).sum();
    }

    // ---------- internals ----------

    private Fine findScoped(String fineId) {
        Fine fine = fines.findById(fineId)
                .orElseThrow(() -> ApiException.notFound("FINE_NOT_FOUND", "Fine not found"));
        TenantGuard.requireBelongsToCurrentTenant(fine.getTenantId());
        return fine;
    }

    private Fine base(String tenantId, String membershipId, String userId, Fine.Type type) {
        Fine fine = new Fine();
        fine.setTenantId(tenantId);
        fine.setMembershipId(membershipId);
        fine.setUserId(userId);
        fine.setType(type);
        return fine;
    }

    private FineDto decorate(Fine fine) {
        Membership member = memberships.findById(fine.getMembershipId()).orElse(null);
        User user = member == null ? null : users.findById(member.getUserId()).orElse(null);
        return FineDto.of(fine, member == null ? null : member.getMembershipNumber(),
                user == null ? null : user.getFullName());
    }

    private static String formatMinor(long amountMinor, String currency) {
        return currency + " " + String.format(Locale.ROOT, "%.2f", amountMinor / 100.0);
    }

    private Fine.Type parseType(String raw) {
        Fine.Type type;
        try {
            type = Fine.Type.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_FINE_TYPE",
                    "Type must be one of LOST, DAMAGED, REPLACEMENT, MANUAL (LATE is automatic)");
        }
        if (type == Fine.Type.LATE) {
            throw ApiException.badRequest("INVALID_FINE_TYPE",
                    "LATE fines are computed automatically at return time");
        }
        return type;
    }
}
