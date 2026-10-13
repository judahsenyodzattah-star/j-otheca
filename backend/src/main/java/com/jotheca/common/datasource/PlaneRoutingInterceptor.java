package com.jotheca.common.datasource;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.common.tenant.PlaneContext;
import com.jotheca.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Assigns the DB plane per request (Phase A). Runs in the DispatcherServlet, i.e. AFTER Spring Security,
 * so reaching here means the caller is already authenticated and (for {@code /api/v1/platform/**})
 * authorized as SUPER_ADMIN by {@code SecurityConfig} — authorization is enforced in the application; the
 * plane only chooses the least-privilege DB role:
 *   GET  /api/v1/platform/** -> PLATFORM_READ (jotheca_readall, read-only, BYPASSRLS)
 *   other /api/v1/platform/** -> TENANT        (jotheca_app, RLS-confined to the armed target library)
 *   everything else           -> TENANT        (jotheca_app)
 * Platform access is audited ({@link AuditAction#PLATFORM_ACCESS}) via the TENANT plane (the read-only
 * role cannot insert; jotheca_app writes a NULL-tenant audit row, permitted by the fail-closed policy).
 */
public class PlaneRoutingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PlaneRoutingInterceptor.class);
    private static final String PLATFORM_PREFIX = "/api/v1/platform/";
    private final AuditService audit;

    public PlaneRoutingInterceptor(AuditService audit) {
        this.audit = audit;
    }

    private static boolean isPlatform(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(PLATFORM_PREFIX);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean platformRead = isPlatform(request) && "GET".equalsIgnoreCase(request.getMethod());
        PlaneContext.set(platformRead ? PlaneContext.PLATFORM_READ : PlaneContext.TENANT);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        boolean platform = isPlatform(request);
        try {
            if (platform) {
                // Audit via the TENANT plane: jotheca_app can insert a NULL-tenant audit row (the read-only
                // role cannot). TenantContext is NOT cleared at this point: TenantResolutionFilter clears it
                // in its own finally, which runs after this callback. So the row's tenant_id is whatever the
                // request actually resolved - the target library for a platform WRITE that armed
                // PlatformWriteContext (changePlan / approve), and NULL for a platform READ, because
                // platform callers send no X-Library-Id. It is therefore NOT guaranteed to be NULL.
                PlaneContext.set(PlaneContext.TENANT);
                audit.log(AuditAction.PLATFORM_ACCESS, "PlatformRequest",
                        request.getMethod() + " " + request.getRequestURI(),
                        Map.of("status", String.valueOf(response.getStatus())));
            }
        } catch (Exception e) {
            log.error("Failed to audit platform access for {} {}", request.getMethod(),
                    request.getRequestURI(), e);
        } finally {
            PlaneContext.clear();
        }
    }
}
