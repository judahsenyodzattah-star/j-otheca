package com.jotheca.analytics;

import com.jotheca.audit.repo.AuditLogRepository;
import com.jotheca.billing.model.LibrarySubscription;
import com.jotheca.billing.model.Payment;
import com.jotheca.billing.repo.LibrarySubscriptionRepository;
import com.jotheca.billing.repo.PaymentRepository;
import com.jotheca.billing.repo.SubscriptionPlanRepository;
import com.jotheca.billing.service.StorageUsageService;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.tenancy.model.LibraryStatus;
import com.jotheca.tenancy.repo.LibraryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform analytics (Phase 9): the SUPER_ADMIN operations dashboard. Cross-tenant by design —
 * this class is platform-plane code and intentionally disjoint from tenant services.
 */
@Service
public class PlatformAnalyticsService {

    public record LibraryBreakdown(long total, long active, long pendingApproval, long suspended) {}

    public record MembershipBreakdown(long total, long approved, long pending) {}

    public record PaymentTotals(long totalPaidMinor, long subscriptionMinor, long storageAddonMinor,
                                long finesMinor, String currency) {}

    public record MrrDto(long mrrMinor, String currency, List<Map<String, Object>> byPlan) {}

    public record PlatformDashboardDto(
            LibraryBreakdown libraries, MembershipBreakdown memberships,
            MrrDto mrr, long activeSubscriptions,
            PaymentTotals payments,
            long storageTotalBytes, long auditEventsLast24h, Instant generatedAt) {}

    private final LibraryRepository libraries;
    private final MembershipRepository memberships;
    private final LibrarySubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final PaymentRepository payments;
    private final StorageUsageService storageUsage;
    private final AuditLogRepository auditLogs;

    public PlatformAnalyticsService(LibraryRepository libraries, MembershipRepository memberships,
                                    LibrarySubscriptionRepository subscriptions,
                                    SubscriptionPlanRepository plans, PaymentRepository payments,
                                    StorageUsageService storageUsage, AuditLogRepository auditLogs) {
        this.libraries = libraries;
        this.memberships = memberships;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.payments = payments;
        this.storageUsage = storageUsage;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public PlatformDashboardDto dashboard() {
        List<LibrarySubscription> active = subscriptions.findByStatus(LibrarySubscription.Status.ACTIVE);

        long mrr = 0;
        java.util.Map<String, long[]> byPlan = new java.util.LinkedHashMap<>();
        for (LibrarySubscription sub : active) {
            var plan = plans.findById(sub.getPlanId()).orElse(null);
            if (plan == null) {
                continue;
            }
            long[] agg = byPlan.computeIfAbsent(plan.getCode(), k -> new long[2]);
            agg[0]++;                          // subscribers
            agg[1] += plan.getPriceMonthlyMinor(); // revenue
            mrr += plan.getPriceMonthlyMinor();
        }
        List<Map<String, Object>> byPlanRows = byPlan.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "plan", e.getKey(), "subscribers", e.getValue()[0], "mrrMinor", e.getValue()[1]))
                .toList();

        return new PlatformDashboardDto(
                new LibraryBreakdown(
                        libraries.count(),
                        libraries.countByStatus(LibraryStatus.ACTIVE),
                        libraries.countByStatus(LibraryStatus.PENDING_APPROVAL),
                        libraries.countByStatus(LibraryStatus.SUSPENDED)),
                new MembershipBreakdown(
                        memberships.count(),
                        memberships.countByStatus(MembershipStatus.APPROVED),
                        memberships.countByStatus(MembershipStatus.PENDING)),
                new MrrDto(mrr, "GHS", byPlanRows),
                active.size(),
                new PaymentTotals(
                        payments.totalPaidMinor(),
                        payments.totalPaidMinorByPurpose(Payment.Purpose.SUBSCRIPTION),
                        payments.totalPaidMinorByPurpose(Payment.Purpose.STORAGE_ADDON),
                        payments.totalPaidMinorByPurpose(Payment.Purpose.FINE),
                        "GHS"),
                storageUsage.totalUsage(),
                auditLogs.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS)),
                Instant.now());
    }
}
