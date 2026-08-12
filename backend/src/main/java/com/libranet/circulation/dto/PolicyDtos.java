package com.libranet.circulation.dto;

import com.libranet.circulation.model.LoanPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public final class PolicyDtos {

    private PolicyDtos() {}

    public record PolicyDto(int loanDays, int renewDays, int maxActiveLoans, int maxRenewals,
                            int maxReservations, int pickupDeadlineHours, int graceDays,
                            long finePerDayMinor, long replacementFeeMinor, String currency) {
        public static PolicyDto from(LoanPolicy p) {
            return new PolicyDto(p.getLoanDays(), p.getRenewDays(), p.getMaxActiveLoans(), p.getMaxRenewals(),
                    p.getMaxReservations(), p.getPickupDeadlineHours(), p.getGraceDays(),
                    p.getFinePerDayMinor(), p.getReplacementFeeMinor(), p.getCurrency());
        }
    }

    public record UpdatePolicyRequest(
            @Min(0) @Max(365) Integer loanDays,
            @Min(0) @Max(365) Integer renewDays,
            @Min(1) @Max(1000) Integer maxActiveLoans,
            @Min(0) @Max(50) Integer maxRenewals,
            @Min(0) @Max(100) Integer maxReservations,
            @Min(0) @Max(24 * 90) Integer pickupDeadlineHours,
            @Min(0) @Max(90) Integer graceDays,
            @Min(0) Long finePerDayMinor,
            @Min(0) Long replacementFeeMinor,
            @Pattern(regexp = "[A-Z]{3}") String currency) {}
}
