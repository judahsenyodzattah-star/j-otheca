package com.libranet.common.tenant;

import com.libranet.common.error.ApiException;
import java.util.Objects;

/** Isolation layer 3 of 9: role + tenant assertions used by every tenant-scoped service. */
public final class TenantGuard {

    private TenantGuard() {}

    public static TenantContext.Current requireRole(String... roles) {
        TenantContext.Current ctx = TenantContext.require();
        if (!ctx.hasAnyRole(roles)) {
            throw ApiException.forbidden("INSUFFICIENT_LIBRARY_ROLE",
                    "This action requires one of: " + String.join(", ", roles));
        }
        return ctx;
    }

    /**
     * IDOR guard (spec §36): direct id lookups bypass Hibernate filters, so every by-id load
     * in tenant scope must be followed by this check. Cross-tenant access answers 404 —
     * the existence of the resource is not revealed.
     */
    public static void requireBelongsToCurrentTenant(String entityTenantId) {
        if (!Objects.equals(entityTenantId, TenantContext.requireLibraryId())) {
            throw ApiException.notFound("NOT_FOUND", "Resource not found");
        }
    }
}
