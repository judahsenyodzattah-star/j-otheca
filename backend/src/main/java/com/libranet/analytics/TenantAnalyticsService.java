package com.libranet.analytics;

import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.model.SubscriptionPlan;
import com.libranet.billing.repo.LibrarySubscriptionRepository;
import com.libranet.billing.repo.SubscriptionPlanRepository;
import com.libranet.billing.service.EntitlementService;
import com.libranet.billing.service.PlanLimits;
import com.libranet.billing.model.Payment;
import com.libranet.billing.repo.PaymentRepository;
import com.libranet.billing.service.StorageUsageService;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.catalog.repo.BranchRepository;
import com.libranet.circulation.model.Loan;
import com.libranet.circulation.model.Reservation;
import com.libranet.circulation.repo.FineRepository;
import com.libranet.circulation.repo.LoanRepository;
import com.libranet.circulation.repo.NotificationRepository;
import com.libranet.circulation.repo.ReservationRepository;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.media.model.BookReview;
import com.libranet.media.model.ReviewReport;
import com.libranet.media.repo.BookReviewRepository;
import com.libranet.media.repo.ReviewReportRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.publishing.repo.BookInterestRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant analytics (Phase 9, spec §33): the LIBRARY_ADMIN dashboard and the member "my library"
 * view. Everything derives from scoped repositories — this module contains no global queries,
 * so a tenant-pinned response can never leak another library's numbers.
 */
@Service
public class TenantAnalyticsService {

    // ---------- DTOs ----------

    public record QuotaBar(long used, long limit) {}

    public record LoanStats(long active, long overdue) {}

    public record ReservationStats(long reserved, long waitingForPickup, long readyForPickup,
                                   long active) {}

    public record BookDemand(String bookId, String title, String slug, long interests) {}

    public record BookRating(String bookId, String title, String slug, double ratingAvg,
                             int ratingCount) {}

    /** LIBRARY_ADMIN dashboard: plan/usage, circulation health and demand signals. */
    public record AdminDashboardDto(
            String planCode, String planName, String subscriptionStatus,
            Instant periodEnd, long daysRemaining,
            QuotaBar members, QuotaBar books, QuotaBar branches, QuotaBar storageBytes,
            long copiesTotal,
            LoanStats loans, ReservationStats reservations,
            long finesOpenCount, long finesOpenMinor, String finesCurrency,
            long pendingReviews, long openReports, long paymentsTotalMinor,
            List<BookDemand> topDemanded, List<BookRating> topRated) {}

    /** Member dashboard: their own loans/fines/reservations/engagement in this library. */
    public record MemberDashboardDto(
            String membershipNumber, String role,
            long activeLoans, long overdueLoans,
            long openFinesCount, long openFinesMinor, String finesCurrency,
            long activeReservations, long interests, long reviews,
            long unreadNotifications) {}

    private final MembershipRepository memberships;
    private final LibrarySubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final EntitlementService entitlements;
    private final StorageUsageService storageUsage;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final BranchRepository branches;
    private final LoanRepository loans;
    private final FineRepository fines;
    private final ReservationRepository reservations;
    private final BookReviewRepository reviews;
    private final ReviewReportRepository reports;
    private final BookInterestRepository interests;
    private final NotificationRepository notifications;
    private final PaymentRepository payments;

    public TenantAnalyticsService(MembershipRepository memberships,
                                  LibrarySubscriptionRepository subscriptions,
                                  SubscriptionPlanRepository plans,
                                  EntitlementService entitlements,
                                  StorageUsageService storageUsage,
                                  BookRepository books, BookCopyRepository copies,
                                  BranchRepository branches, LoanRepository loans,
                                  FineRepository fines, ReservationRepository reservations,
                                  BookReviewRepository reviews, ReviewReportRepository reports,
                                  BookInterestRepository interests,
                                  NotificationRepository notifications,
                                  PaymentRepository payments) {
        this.memberships = memberships;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.storageUsage = storageUsage;
        this.books = books;
        this.copies = copies;
        this.branches = branches;
        this.loans = loans;
        this.fines = fines;
        this.reservations = reservations;
        this.reviews = reviews;
        this.reports = reports;
        this.interests = interests;
        this.notifications = notifications;
        this.payments = payments;
    }

    /** Sum of this library's PAID payments (renewals + add-ons + online fine receipts). */
    private long paymentTotalFor(String tenantId) {
        return payments.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(p -> p.getStatus() == Payment.Status.PAID)
                .mapToLong(Payment::getAmountMinor).sum();
    }

    // ---------- admin dashboard ----------

    @Transactional(readOnly = true)
    public AdminDashboardDto adminDashboard() {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        String tenantId = ctx.libraryId();
        PlanLimits limits = entitlements.limitsFor(tenantId);

        LibrarySubscription sub = subscriptions.findByTenantId(tenantId).orElse(null);
        SubscriptionPlan plan = sub == null ? null : plans.findById(sub.getPlanId()).orElse(null);
        Instant periodEnd = sub == null ? null : sub.getCurrentPeriodEnd();
        long daysRemaining = periodEnd == null ? 0
                : Math.max(0, ChronoUnit.DAYS.between(Instant.now(), periodEnd));

        long storageCapBytes = limits.storageGb() * 1024L * 1024L * 1024L;

        long reserved = reservations.countByTenantIdAndStatus(tenantId, Reservation.Status.RESERVED);
        long waiting = reservations.countByTenantIdAndStatus(tenantId, Reservation.Status.WAITING_FOR_PICKUP);
        long ready = reservations.countByTenantIdAndStatus(tenantId, Reservation.Status.READY_FOR_PICKUP);

        List<BookDemand> demanded = interests.topDemanded(tenantId, PageRequest.of(0, 5)).stream()
                .map(row -> {
                    Book book = books.findById((String) row[0]).orElse(null);
                    return new BookDemand((String) row[0],
                            book == null ? "(deleted title)" : book.getTitle(),
                            book == null ? null : book.getSlug(),
                            (long) row[1]);
                }).toList();
        List<BookRating> rated = books
                .findByTenantIdAndPublishedTrueAndRatingCountGreaterThanEqualOrderByRatingAvgDescRatingCountDesc(
                        tenantId, 1, PageRequest.of(0, 5))
                .stream()
                .map(b -> new BookRating(b.getId(), b.getTitle(), b.getSlug(), b.getRatingAvg(),
                        b.getRatingCount()))
                .toList();

        return new AdminDashboardDto(
                plan == null ? null : plan.getCode(),
                plan == null ? null : plan.getName(),
                sub == null ? "NONE" : sub.getStatus().name(),
                periodEnd, daysRemaining,
                new QuotaBar(memberships.countByTenantIdAndStatus(tenantId, MembershipStatus.APPROVED),
                        limits.maxMembers()),
                new QuotaBar(books.countByTenantId(tenantId), limits.maxBooks()),
                new QuotaBar(branches.countByTenantId(tenantId), limits.maxBranches()),
                new QuotaBar(storageUsage.usageOf(tenantId), storageCapBytes),
                copies.countByTenantId(tenantId),
                new LoanStats(loans.countByTenantIdAndStatus(tenantId, Loan.Status.ACTIVE),
                        loans.countByTenantIdAndStatusAndDueAtBefore(tenantId, Loan.Status.ACTIVE,
                                Instant.now())),
                new ReservationStats(reserved, waiting, ready, reserved + waiting + ready),
                fines.countByTenantIdAndStatus(tenantId, com.libranet.circulation.model.Fine.Status.OPEN),
                fines.openBalanceForTenant(tenantId), "GHS",
                reviews.countByTenantIdAndStatus(tenantId, BookReview.ReviewStatus.PENDING),
                reports.countByTenantIdAndStatus(tenantId, ReviewReport.ReportStatus.OPEN),
                paymentTotalFor(tenantId),
                demanded, rated);
    }

    // ---------- member "me" dashboard ----------

    @Transactional(readOnly = true)
    public MemberDashboardDto memberDashboard() {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        String membershipId = ctx.membershipId();
        String userId = com.libranet.common.web.SecurityUsers.current().id();

        List<Loan> active = loans.findByTenantIdAndMembershipIdAndStatus(
                tenantId, membershipId, Loan.Status.ACTIVE);
        long overdue = active.stream().filter(l -> l.getDueAt() != null && l.getDueAt().isBefore(Instant.now())).count();

        return new MemberDashboardDto(
                null, ctx.role(),
                active.size(), overdue,
                fines.countByTenantIdAndMembershipIdAndStatus(tenantId, membershipId,
                        com.libranet.circulation.model.Fine.Status.OPEN),
                fines.openBalanceForMember(tenantId, membershipId), "GHS",
                reservations.countByTenantIdAndMembershipIdAndStatusIn(tenantId, membershipId,
                        List.of(Reservation.Status.RESERVED, Reservation.Status.WAITING_FOR_PICKUP,
                                Reservation.Status.READY_FOR_PICKUP)),
                interests.countByTenantIdAndMembershipId(tenantId, membershipId),
                reviews.countByTenantIdAndMembershipId(tenantId, membershipId),
                notifications.countByTenantIdAndUserIdAndReadAtIsNull(tenantId, userId));
    }
}
