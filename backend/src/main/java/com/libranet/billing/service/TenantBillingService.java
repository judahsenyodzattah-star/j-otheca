package com.libranet.billing.service;

import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.model.SubscriptionPlan;
import com.libranet.billing.repo.LibrarySubscriptionRepository;
import com.libranet.billing.repo.SubscriptionPlanRepository;
import com.libranet.billing.service.PaymentService.CheckoutDetails;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.membership.model.LibraryRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/** Tenant-plane billing self-service (Phase 9): subscription view, renewals, storage add-ons. */
@Service
public class TenantBillingService {

    /** LIBRARY_ADMIN's billing view. */
    public record SubscriptionViewDto(String planCode, String planName, long priceMonthlyMinor,
                                      String currency, String status, Instant periodStart, Instant periodEnd,
                                      long daysRemaining, int storageAddonGb, PlanLimits effectiveLimits) {}

    public record PaymentHistoryDto(String reference, String purpose, long amountMinor, String currency,
                                    String status, Instant createdAt, Instant paidAt) {}

    public record StorageAddonRequest(@jakarta.validation.constraints.Min(1)
                                      @jakarta.validation.constraints.Max(1000) int gb) {}

    private final LibrarySubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final com.libranet.billing.repo.PaymentRepository payments;
    private final EntitlementService entitlements;
    private final PaymentService paymentService;
    private final UserRepository users;
    private final long addonPricePerGbMinor;

        private final PermissionService permissions;

    public TenantBillingService(LibrarySubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
                                com.libranet.billing.repo.PaymentRepository payments,
                                EntitlementService entitlements, PaymentService paymentService,
                                UserRepository users,
                                @Value("${app.billing.storage-addon-price-per-gb-minor:1000}") long addonPricePerGbMinor,
                                PermissionService permissions) {
        this.permissions = permissions;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.payments = payments;
        this.entitlements = entitlements;
        this.paymentService = paymentService;
        this.users = users;
        this.addonPricePerGbMinor = addonPricePerGbMinor;
    }

    @Transactional(readOnly = true)
    public SubscriptionViewDto current() {
        TenantContext.Current ctx = admin();
        LibrarySubscription sub = subscriptions.findByTenantId(ctx.libraryId())
                .orElseThrow(() -> ApiException.notFound("SUBSCRIPTION_NOT_FOUND", "No subscription for this library"));
        SubscriptionPlan plan = plans.findById(sub.getPlanId())
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "Plan not found"));
        long days = sub.getCurrentPeriodEnd() == null ? 0
                : Math.max(0, ChronoUnit.DAYS.between(Instant.now(), sub.getCurrentPeriodEnd()));
        return new SubscriptionViewDto(plan.getCode(), plan.getName(), plan.getPriceMonthlyMinor(),
                plan.getCurrency(), sub.getStatus().name(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(),
                days, sub.getStorageAddonGb(), entitlements.limitsFor(ctx.libraryId()));
    }

    /** Renew for one 30-day cycle at the CURRENT plan price — confirm flips period/status. */
    @Transactional
    public CheckoutDetails renew() {
        TenantContext.Current ctx = admin();
        LibrarySubscription sub = subscriptions.findByTenantId(ctx.libraryId())
                .orElseThrow(() -> ApiException.notFound("SUBSCRIPTION_NOT_FOUND", "No subscription for this library"));
        SubscriptionPlan plan = plans.findById(sub.getPlanId())
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "Plan not found"));
        return paymentService.initiateRenewal(currentUser(), ctx.libraryId(), sub, plan);
    }

    /** Buy {@code gb} extra storage, stacked on the plan cap after payment confirms. */
    @Transactional
    public CheckoutDetails buyStorageAddon(int gb) {
        TenantContext.Current ctx = admin();
        return paymentService.initiateStorageAddon(currentUser(), ctx.libraryId(), gb,
                addonPricePerGbMinor, "GHS");
    }

    /** Tenant-visible platform payment history (rail rows + renewals/add-ons). */
    @Transactional(readOnly = true)
    public List<PaymentHistoryDto> history() {
        TenantContext.Current ctx = admin();
        return payments.findByTenantIdOrderByCreatedAtDesc(ctx.libraryId()).stream()
                .map(p -> new PaymentHistoryDto(p.getProviderReference(), p.getPurpose().name(),
                        p.getAmountMinor(), p.getCurrency(), p.getStatus().name(),
                        p.getCreatedAt(), p.getPaidAt()))
                .toList();
    }

    private TenantContext.Current admin() {
        return permissions.require(LibraryPerm.BILLING_MANAGE);
    }

    private User currentUser() {
        return users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    }

    /** Price accessor for tests/docs. */
    public long addonPricePerGbMinor() {
        // pricing is config (ops-tunable), not a plan constant — add-on price is uniform across plans
        return addonPricePerGbMinor;
    }

    /** Keeps Map import used for forward-compat payment metadata views. */
    static Map<String, Object> emptyDetails() {
        return Map.of();
    }
}
