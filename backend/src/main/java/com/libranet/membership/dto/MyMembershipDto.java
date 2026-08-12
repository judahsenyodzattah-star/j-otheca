package com.libranet.membership.dto;

import com.libranet.membership.model.Membership;
import com.libranet.tenancy.model.Library;
import java.time.Instant;

/** The acting user's own membership — powers "my libraries" + the digital membership card (spec §25). */
public record MyMembershipDto(String membershipId, String librarySlug, String libraryUid, String libraryName,
                              String libraryType, String city, String role, String membershipType, String status,
                              String membershipNumber, Instant joinedAt, Instant expiresAt) {

    public static MyMembershipDto from(Membership m, Library l) {
        return new MyMembershipDto(m.getId(), l != null ? l.getSlug() : null, l != null ? l.getUid() : null,
                l != null ? l.getName() : null, l != null ? l.getType().name() : null,
                l != null ? l.getCity() : null,
                m.getRole().name(), m.getMembershipType().name(), m.getStatus().name(),
                m.getMembershipNumber(), m.getJoinedAt(), m.getExpiresAt());
    }
}
