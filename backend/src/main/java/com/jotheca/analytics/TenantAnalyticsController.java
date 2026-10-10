package com.jotheca.analytics;

import com.jotheca.analytics.TenantAnalyticsService.AdminDashboardDto;
import com.jotheca.analytics.TenantAnalyticsService.MemberDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant analytics (Phase 9, spec §33): the admin dashboard and the member "my library" view. */
@RestController
@RequestMapping("/api/v1/tenant/analytics")
public class TenantAnalyticsController {

    private final TenantAnalyticsService analytics;

    public TenantAnalyticsController(TenantAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** LIBRARY_ADMIN: plan/usage, circulation health and demand signals for this library only. */
    @GetMapping("/dashboard")
    public AdminDashboardDto dashboard() {
        return analytics.adminDashboard();
    }

    /** Any approved member: their own loans/fines/reservations/engagement in this library. */
    @GetMapping("/me")
    public MemberDashboardDto me() {
        return analytics.memberDashboard();
    }
}
