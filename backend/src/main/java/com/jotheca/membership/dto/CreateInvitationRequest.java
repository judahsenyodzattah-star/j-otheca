package com.jotheca.membership.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateInvitationRequest(
        @Size(max = 60) String slug,
        @Size(max = 20) String membershipType,
        Boolean autoApprove,
        @Min(1) @Max(100000) Integer maxUses,
        Instant expiresAt) {}
