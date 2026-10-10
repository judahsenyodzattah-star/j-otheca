package com.jotheca.circulation.dto;

import com.jotheca.circulation.model.Fine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class FineDtos {

    private FineDtos() {}

    public record FineDto(String id, String loanId, String membershipId, String membershipNumber,
                          String memberName, String type, long amountMinor, String currency,
                          String status, String note, String settlement, String settledAt, String createdAt) {
        public static FineDto of(Fine f, String membershipNumber, String memberName) {
            return new FineDto(f.getId(), f.getLoanId(), f.getMembershipId(), membershipNumber, memberName,
                    f.getType().name(), f.getAmountMinor(), f.getCurrency(), f.getStatus().name(),
                    f.getNote(), f.getSettlement(),
                    f.getSettledAt() == null ? null : f.getSettledAt().toString(),
                    f.getCreatedAt() == null ? null : f.getCreatedAt().toString());
        }
    }

    /** Staff assessment. LOST/DAMAGED close the referenced loan and mark the copy accordingly. */
    public record AssessFineRequest(
            @NotBlank @Size(max = 36) String membershipId,
            @Size(max = 36) String loanId,
            @NotBlank String type,                    // LOST | DAMAGED | REPLACEMENT | MANUAL
            @Positive Long amountMinor,               // optional for LOST/REPLACEMENT (policy default) — required for DAMAGED/MANUAL
            @Size(max = 500) String note) {}

    public record SettleFineRequest(
            @NotNull @PositiveOrZero Long amountMinor,  // what was actually collected (≤ amount, rest is waived)
            @NotBlank @Size(max = 200) String settlement) {}
}
