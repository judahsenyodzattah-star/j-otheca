package com.jotheca.common.web;

import com.jotheca.common.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUsers {

    private SecurityUsers() {}

    public static AuthUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return user;
    }
}
