package com.libranet.identity.dto;

import com.libranet.identity.model.User;
import java.time.Instant;

public record UserDto(String id, String email, String fullName, String phone,
                      String globalRole, boolean emailVerified, Instant createdAt) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(),
                u.getGlobalRole().name(), u.getEmailVerifiedAt() != null, u.getCreatedAt());
    }
}
