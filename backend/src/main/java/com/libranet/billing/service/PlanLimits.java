package com.libranet.billing.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Typed view over subscription_plans.limits JSON (spec §16: never hard-code limits in code;
 * missing keys fall back to conservative defaults so a new key can't silently grant anything).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanLimits(
        int storageGb,
        int maxMembers,
        int maxBooks,
        int maxStaff,
        int aiCallsMonthly,
        int maxBranches,
        boolean reservations,
        List<String> notifications,
        String analytics,
        boolean barcodeQr,
        boolean apiAccess,
        boolean sso,
        boolean prioritySupport) {

    /** Compact constructor applies conservative defaults for any keys missing from plan JSON. */
    public PlanLimits {
        if (notifications == null) notifications = List.of("in_app");
        if (analytics == null) analytics = "basic";
    }

    /** Most restrictive fallback when a plan cannot be resolved at all. */
    public static PlanLimits conservative() {
        return new PlanLimits(1, 25, 250, 1, 0, 1, false, null, null, false, false, false, false);
    }
}
