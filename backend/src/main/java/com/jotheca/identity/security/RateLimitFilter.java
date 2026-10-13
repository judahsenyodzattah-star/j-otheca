package com.jotheca.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jotheca.common.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
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
    private final Set<String> trustedProxies;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public RateLimitFilter(@Value("${app.rate-limit-auth-per-minute:30}") int limit,
                           @Value("${app.rate-limit.trusted-proxies:}") String trustedProxiesCsv,
                           RateLimiter rateLimiter) {
        this.limit = limit;
        this.rateLimiter = rateLimiter;
        this.trustedProxies = parseTrustedProxies(trustedProxiesCsv);
    }

    private static Set<String> parseTrustedProxies(String csv) {
        Set<String> out = new HashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return Set.copyOf(out);
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

    /**
     * R2 fix: honor {@code X-Forwarded-For} ONLY when the immediate peer is a configured trusted proxy
     * (app.rate-limit.trusted-proxies). With the default (empty) set the header is ignored entirely and
     * the socket peer is used, so a client cannot spoof the header to evade per-IP limits. Configure the
     * real ingress/load-balancer address(es) in deployed environments. (Exact-match; CIDR is a follow-up.)
     */
    String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote != null && trustedProxies.contains(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return remote;
    }
}
