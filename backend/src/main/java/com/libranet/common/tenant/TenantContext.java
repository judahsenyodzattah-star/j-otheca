package com.libranet.common.tenant;

import com.libranet.common.error.ApiException;

/**
 * The resolved tenant for the current request (isolation layer 4 of 9).
 * Set ONLY by TenantResolutionFilter after membership validation — never from client data directly.
 * Fail-closed: tenant-scoped work without a resolved context throws.
 */
public final class TenantContext {

    /** @param role library-level role of the acting user in THIS library (LIBRARY_ADMIN/LIBRARIAN/PATRON) */
    public record Current(String libraryId, String libraryUid, String membershipId, String role) {
        public boolean hasAnyRole(String... roles) {
            for (String r : roles) {
                if (r.equals(role)) return true;
            }
            return false;
        }
    }

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Current current) { CURRENT.set(current); }
    public static Current get() { return CURRENT.get(); }

    public static String requireLibraryId() {
        Current c = CURRENT.get();
        if (c == null) {
            throw ApiException.forbidden("TENANT_REQUIRED", "No tenant context resolved for this request");
        }
        return c.libraryId();
    }

    public static Current require() {
        Current c = CURRENT.get();
        if (c == null) {
            throw ApiException.forbidden("TENANT_REQUIRED", "No tenant context resolved for this request");
        }
        return c;
    }

    public static void clear() { CURRENT.remove(); }
}
