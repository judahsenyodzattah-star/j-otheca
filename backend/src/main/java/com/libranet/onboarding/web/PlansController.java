package com.libranet.onboarding.web;

import com.libranet.billing.dto.PlanDto;
import com.libranet.billing.repo.SubscriptionPlanRepository;
import com.libranet.billing.service.EntitlementService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public pricing/plan catalog — feeds the onboarding plan picker (spec §16). */
@RestController
@RequestMapping("/api/v1/plans")
public class PlansController {

    private final SubscriptionPlanRepository plans;
    private final EntitlementService entitlements;

    public PlansController(SubscriptionPlanRepository plans, EntitlementService entitlements) {
        this.plans = plans;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<PlanDto> list() {
        return plans.findByActiveTrueOrderBySortOrder().stream()
                .map(p -> PlanDto.from(p, entitlements.parse(p)))
                .toList();
    }
}
