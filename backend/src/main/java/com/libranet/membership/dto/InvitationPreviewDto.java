package com.libranet.membership.dto;

import com.libranet.membership.model.Invitation;
import com.libranet.tenancy.model.Library;

/** Public preview when someone opens a join link (spec §14). No internal identifiers. */
public record InvitationPreviewDto(String librarySlug, String libraryName, String libraryType,
                                   String city, String country, String description,
                                   String membership, boolean requiresApproval) {

    public static InvitationPreviewDto from(Invitation i, Library l) {
        return new InvitationPreviewDto(l.getSlug(), l.getName(), l.getType().name(),
                l.getCity(), l.getCountry(), l.getDescription(),
                i.getMembershipType().name(), !i.isAutoApprove());
    }
}
