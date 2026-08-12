package com.libranet.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.libranet.common.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Brute-force / credential-stuffing protection for auth endpoints (spec §36).
 * Backend selected by app.rate-limit.backend: memory (default, single node) or
 * redis (distributed, Phase 8 — set spring.redis.* when enabling).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/mfa/verify");

    private final long windowMs = 60_000;
    private final int limit;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public RateLimitFilter(@Value("${app.rate-limit-auth-per-minute:30}") int limit, RateLimiter rateLimiter) {
        this.limit = limit;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !PROTECTED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getRequestURI().substring("/api/v1/auth/".length()) + ":" + clientIp(request);
        if (!rateLimiter.tryAcquire(key, limit, windowMs)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(
                    ApiError.of(429, "RATE_LIMITED", "Too many attempts. Please try again shortly.")));
            return;
        }
        chain.doFilter(request, response);
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
