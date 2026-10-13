package com.jotheca.membership.dto;

import com.jotheca.membership.model.Invitation;
import java.time.Instant;

public record InvitationDto(String id, String slug, String code, String joinUrl,
                            String membershipType, boolean autoApprove, Integer maxUses,
                            int usedCount, Instant expiresAt, boolean active) {

    public static InvitationDto from(Invitation i, String publicBaseUrl) {
        return new InvitationDto(i.getId(), i.getSlug(), i.getCode(), publicBaseUrl + "/join/" + i.getSlug(),
                i.getMembershipType().name(), i.isAutoApprove(), i.getMaxUses(),
                i.getUsedCount(), i.getExpiresAt(), i.isActive());
    }
}
