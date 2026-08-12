package com.libranet.billing.dto;

import com.libranet.billing.model.SubscriptionPlan;
import com.libranet.billing.service.PlanLimits;

public record PlanDto(String code, String name, String description,
                      long priceMonthlyMinor, String currency, PlanLimits limits) {

    public static PlanDto from(SubscriptionPlan plan, PlanLimits limits) {
        return new PlanDto(plan.getCode(), plan.getName(), plan.getDescription(),
                plan.getPriceMonthlyMinor(), plan.getCurrency(), limits);
    }
}
