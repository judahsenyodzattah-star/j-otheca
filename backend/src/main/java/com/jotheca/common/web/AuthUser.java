package com.jotheca.common.web;

/** Authenticated principal placed in the SecurityContext by JwtAuthFilter. Carries NO tenant authority. */
public record AuthUser(String id, String email, String globalRole) {

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(globalRole);
    }
}
