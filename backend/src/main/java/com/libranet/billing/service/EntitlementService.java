package com.libranet.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.model.SubscriptionPlan;
import com.libranet.billing.repo.LibrarySubscriptionRepository;
import com.libranet.billing.repo.SubscriptionPlanRepository;
import com.libranet.common.error.ApiException;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entrance to "what is this library allowed to do" (spec §16).
 * Enforcement points call guard methods here; the app never scatters limit checks.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final LibrarySubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final MembershipRepository memberships;
    private final SubscriptionLifecycleService lifecycle;
    private final ObjectMapper mapper = new ObjectMapper();

    public EntitlementService(LibrarySubscriptionRepository subscriptions,
                              SubscriptionPlanRepository plans,
                              MembershipRepository memberships,
                              SubscriptionLifecycleService lifecycle) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.memberships = memberships;
        this.lifecycle = lifecycle;
    }

    @Transactional(readOnly = true)
    public PlanLimits limitsFor(String libraryId) {
        return subscriptions.findByTenantId(libraryId)
                // Phase 9 lazy expiry: an overdue ACTIVE subscription flips to EXPIRED here
                // (own transaction — never rolled back by this read).
                .map(sub -> {
                    lifecycle.expireIfOverdue(sub.getId());
                    return subscriptions.findById(sub.getId()).orElse(sub);
                })
                .filter(s -> s.getStatus() == LibrarySubscription.Status.ACTIVE)
                .flatMap(s -> plans.findById(s.getPlanId())
                        .map(plan -> withStorageAddon(parse(plan), s.getStorageAddonGb())))
                .orElseGet(() -> {
                    // Grace fallback: a tenant without an active subscription gets the most
                    // conservative limits rather than an outage. Provisioning always attaches one.
                    log.warn("library {} has no active subscription; applying conservative limits", libraryId);
                    return plans.findByCode("STARTER").map(this::parse).orElseGet(PlanLimits::conservative);
                });
    }

    /** Effective limits = plan limits + purchased storage add-on (both data, never code). */
    private PlanLimits withStorageAddon(PlanLimits limits, int addonGb) {
        if (addonGb <= 0) {
            return limits;
        }
        return new PlanLimits(limits.storageGb() + addonGb, limits.maxMembers(), limits.maxBooks(),
                limits.maxStaff(), limits.aiCallsMonthly(), limits.maxBranches(), limits.reservations(),
                limits.notifications(), limits.analytics(), limits.barcodeQr(), limits.apiAccess(),
                limits.sso(), limits.prioritySupport());
    }

    /** Guard: approving a member must not exceed the plan's member cap. */
    @Transactional(readOnly = true)
    public void requireMemberCapacity(String libraryId) {
        PlanLimits limits = limitsFor(libraryId);
        long approved = memberships.countByTenantIdAndStatus(libraryId, MembershipStatus.APPROVED);
        if (approved >= limits.maxMembers()) {
            throw limit("members", limits.maxMembers(), approved);
        }
    }

    /** Guard: appointing staff (LIBRARY_ADMIN/LIBRARIAN) must not exceed the plan's staff cap. */
    @Transactional(readOnly = true)
    public void requireStaffCapacity(String libraryId) {
        PlanLimits limits = limitsFor(libraryId);
        long staff = memberships.count((root, q, cb) -> cb.and(
                cb.equal(root.get("tenantId"), libraryId),
                cb.in(root.get("role")).value(LibraryRole.LIBRARY_ADMIN).value(LibraryRole.LIBRARIAN),
                cb.equal(root.get("status"), MembershipStatus.APPROVED)));
        if (staff >= limits.maxStaff()) {
            throw limit("staff", limits.maxStaff(), staff);
        }
    }

    /**
     * Guard: catalog size must stay under the plan's book cap. The counting query is supplied by the
     * caller so this module never depends on catalog repositories.
     */
    @Transactional(readOnly = true)
    public void requireBookCapacity(String libraryId, LongSupplier currentBooks) {
        PlanLimits limits = limitsFor(libraryId);
        long books = currentBooks.getAsLong();
        if (books >= limits.maxBooks()) {
            throw limit("books", limits.maxBooks(), books);
        }
    }

    /** Guard: branch count must stay under the plan's branch cap. */
    @Transactional(readOnly = true)
    public void requireBranchCapacity(String libraryId, LongSupplier currentBranches) {
        PlanLimits limits = limitsFor(libraryId);
        long branches = currentBranches.getAsLong();
        if (branches >= limits.maxBranches()) {
            throw limit("branches", limits.maxBranches(), branches);
        }
    }

    /**
     * Guard: media storage HARD-CHECK (Phase 7). {@code used + additional} must stay under the
     * plan's storage cap — the cap comes from subscription_plans.limits JSON, never code.
     * The counting query is supplied by the caller so this module never depends on media repos.
     */
    @Transactional(readOnly = true)
    public void requireStorageCapacity(String libraryId, LongSupplier currentBytes, long additionalBytes) {
        long cap = limitsFor(libraryId).storageGb() * 1024L * 1024L * 1024L;
        long used = currentBytes.getAsLong();
        if (used + additionalBytes > cap) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PLAN_LIMIT_EXCEEDED",
                    "Your plan allows " + limitsFor(libraryId).storageGb() + " GB of media storage (currently "
                            + (used / (1024 * 1024)) + " MB used). Upgrade the library's subscription to store more.",
                    Map.of("resource", "storageBytes", "limitBytes", cap,
                            "currentBytes", used, "additionalBytes", additionalBytes));
        }
    }

    /** Guard: reservations are a plan feature (spec §16, e.g. STARTER has none). */
    @Transactional(readOnly = true)
    public void requireReservationsEnabled(String libraryId) {
        if (!limitsFor(libraryId).reservations()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FEATURE_NOT_IN_PLAN",
                    "Reservations are not included in this library's plan. Upgrade to enable member reservations.");
        }
    }

    private ApiException limit(String resource, long limit, long current) {
        return new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PLAN_LIMIT_EXCEEDED",
                "Your plan allows " + limit + " " + resource + " (currently " + current
                        + "). Upgrade the library's subscription to add more.",
                Map.of("resource", resource, "limit", limit, "current", current));
    }

    public PlanLimits parse(SubscriptionPlan plan) {
        if (plan == null) {
            return PlanLimits.conservative();
        }
        try {
            return mapper.readValue(plan.getLimitsJson(), PlanLimits.class);
        } catch (Exception e) {
            throw new IllegalStateException("Plan limits JSON for " + plan.getCode() + " is invalid", e);
        }
    }
}
