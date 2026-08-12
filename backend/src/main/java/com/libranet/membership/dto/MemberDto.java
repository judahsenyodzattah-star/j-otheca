package com.libranet.membership.dto;

import com.libranet.identity.model.User;
import com.libranet.membership.model.Membership;
import java.time.Instant;

/** Staff-facing member row (admins/librarians only). */
public record MemberDto(String id, String userId, String email, String fullName,
                        String role, String membershipType, String status,
                        String membershipNumber, Instant joinedAt, Instant expiresAt, Instant requestedAt) {

    public static MemberDto from(Membership m, User u) {
        return new MemberDto(m.getId(), m.getUserId(),
                u != null ? u.getEmail() : null, u != null ? u.getFullName() : null,
                m.getRole().name(), m.getMembershipType().name(), m.getStatus().name(),
                m.getMembershipNumber(), m.getJoinedAt(), m.getExpiresAt(), m.getCreatedAt());
    }
}
