package com.libranet.tenancy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.libranet.common.error.ApiError;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.web.AuthUser;
import com.libranet.membership.model.Membership;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import com.libranet.tenancy.repo.LibraryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Isolation layer 2 of 9. For every request under /api/v1/tenant/** this filter:
 * <ol>
 *   <li>reads the REQUESTED library from {@code X-Library-Id} (a library UID like LIB-PU2026),</li>
 *   <li>VALIDATES it server-side: library exists, is ACTIVE, and the authenticated user holds an
 *       APPROVED membership in it — the header is never trusted on its own,</li>
 *   <li>sets {@link TenantContext} (fail-closed; cleared in a finally block),</li>
 *   <li>arms the Hibernate {@code tenantFilter} on the request-bound persistence context.</li>
 * </ol>
 */
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantResolutionFilter.class);
    private static final String TENANT_PATH_PREFIX = "/api/v1/tenant/";
    private static final String HEADER = "X-Library-Id";
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final LibraryRepository libraries;
    private final MembershipRepository memberships;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantResolutionFilter(LibraryRepository libraries, MembershipRepository memberships) {
        this.libraries = libraries;
        this.memberships = memberships;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (request.getRequestURI().startsWith(TENANT_PATH_PREFIX)) {
                resolve(request);
            }
            chain.doFilter(request, response);
        } catch (ApiException ex) {
            response.setStatus(ex.status().value());
            response.setContentType("application/json");
            response.getWriter().write(JSON.writeValueAsString(
                    ApiError.of(ex.status().value(), ex.code(), ex.getMessage())));
        } finally {
            TenantContext.clear();
        }
    }

    private void resolve(HttpServletRequest request) {
        String uid = request.getHeader(HEADER);
        if (uid == null || uid.isBlank()) {
            throw ApiException.badRequest("TENANT_HEADER_REQUIRED", "Missing " + HEADER + " header");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            return; // unauthenticated: security layer will answer 401; no context is set (fail-closed)
        }
        Library library = libraries.findByUid(uid.trim())
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Library not found"));
        if (library.getStatus() != LibraryStatus.ACTIVE) {
            throw ApiException.forbidden("TENANT_INACTIVE", "This library is not active");
        }
        Membership membership = memberships
                .findByTenantIdAndUserIdAndStatus(library.getId(), user.id(), MembershipStatus.APPROVED)
                .orElseThrow(() -> ApiException.forbidden("NO_MEMBERSHIP",
                        "You are not an approved member of this library"));

        // Also rejects stale/expired members without a query change:
        if (membership.getExpiresAt() != null && membership.getExpiresAt().isBefore(java.time.Instant.now())) {
            throw ApiException.forbidden("MEMBERSHIP_EXPIRED", "Your membership in this library has expired");
        }

        TenantContext.set(new TenantContext.Current(
                library.getId(), library.getUid(), membership.getId(), membership.getRole().name()));
        armHibernateFilter(library.getId());
    }

    private void armHibernateFilter(String libraryId) {
        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.getEnabledFilter("tenantFilter") == null) {
                session.enableFilter("tenantFilter").setParameter("tenantId", libraryId);
            }
        } catch (Exception e) {
            log.warn("tenant filter could not be armed: {}", e.getMessage());
        }
    }
}
