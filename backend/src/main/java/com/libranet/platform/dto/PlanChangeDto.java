package com.libranet.platform.dto;

import jakarta.validation.constraints.NotBlank;

/** SUPER_ADMIN plan management (Phase 9): assign a plan to a library's subscription. */
public final class PlanChangeDto {

    private PlanChangeDto() {}

    public record ChangePlanRequest(@NotBlank String planCode, String reason) {}

    public record PlanChangeResult(String libraryId, String libraryUid, String fromPlan, String toPlan,
                                   String direction, String reason) {}
}
