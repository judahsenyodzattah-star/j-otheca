package com.libranet.analytics;

import com.libranet.analytics.PlatformAnalyticsService.PlatformDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN analytics (Phase 9). Guarded by role in SecurityConfig; cross-tenant by design. */
@RestController
@RequestMapping("/api/v1/platform/analytics")
public class PlatformAnalyticsController {

    private final PlatformAnalyticsService analytics;

    public PlatformAnalyticsController(PlatformAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Platform operations dashboard: libraries, memberships, MRR, payments, storage, audit volume. */
    @GetMapping("/dashboard")
    public PlatformDashboardDto dashboard() {
        return analytics.dashboard();
    }
}
