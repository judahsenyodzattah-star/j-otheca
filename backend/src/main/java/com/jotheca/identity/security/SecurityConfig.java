package com.jotheca.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jotheca.common.error.ApiError;
import com.jotheca.tenancy.service.TenantResolutionFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Bean
    SecurityFilterChain api(HttpSecurity http,
                            JwtAuthFilter jwtAuthFilter,
                            RateLimitFilter rateLimitFilter,
                            TenantResolutionFilter tenantResolutionFilter,
                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            // Stateless JWT API: no server-side session exists for CSRF to attack (spec §36 —
            // CSRF protection returns with any future cookie-based transport).
            .csrf(csrf -> csrf.disable())
            // Honor the configured origin allow-list (app.cors-allowed-origin-patterns /
            // CORS_ORIGINS) via the corsSource bean — never a hard-coded "*". Staging/prod
            // set CORS_ORIGINS and rely on it being enforced (audit D2; pentest checklist).
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error", "/actuator/health",
                                 "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                 // demo web mirror (static assets only; every API call behind
                                 // it still carries a real JWT + tenant header)
                                 "/", "/index.html", "/app", "/app/**").permitAll()
                // Public auth operations are enumerated EXACTLY (Phase 8 hardening): anything
                // else under /api/v1/auth/** and /api/v1/me/** requires a verified access token.
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                 "/api/v1/auth/logout", "/api/v1/auth/verify-email",
                                 "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                 "/api/v1/auth/mfa/verify").permitAll()
                .requestMatchers("/api/v1/join/**", "/api/v1/billing/webhooks/**", "/api/v1/billing/dev/**").permitAll()
                .requestMatchers("/api/v1/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/libraries", "/api/v1/libraries/*", "/api/v1/plans").permitAll()
                .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "UNAUTHENTICATED", "Authentication required"))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "ACCESS_DENIED", "You do not have access to this resource")))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentTypeOptions(c -> {})
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // Phase 8 headers review: strict CSP for the JSON API planes (Swagger UI keeps its
                // own workable policy by being outside /api/**), plus a locked-down Permissions-Policy.
                .addHeaderWriter((req, res) -> {
                    if (req.getRequestURI() != null && req.getRequestURI().startsWith("/api/")) {
                        res.setHeader("Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
                    }
                    res.setHeader("Permissions-Policy",
                            "camera=(), microphone=(), payment=(), usb=(), bluetooth=()");
                }))
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(jwtAuthFilter, RateLimitFilter.class)
            .addFilterAfter(tenantResolutionFilter, JwtAuthFilter.class);
        return http.build();
    }

    // Prevent double-execution: these @Component filters belong ONLY inside the Spring Security chain
    // (we control their order there), not additionally in the plain servlet filter chain.
    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilterRegistration(TenantResolutionFilter filter) {
        return disabled(filter);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsSource(@Value("${app.cors-allowed-origin-patterns:*}") String patterns) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(patterns.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Library-Id"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeJson(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(JSON.writeValueAsString(ApiError.of(status, code, message)));
    }
}
