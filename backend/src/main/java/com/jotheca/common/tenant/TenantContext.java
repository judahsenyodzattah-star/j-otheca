package com.jotheca.common.tenant;

import com.jotheca.common.error.ApiException;

/**
 * The resolved tenant for the current request (isolation layer 4 of 9).
 * Set ONLY by TenantResolutionFilter after membership validation — never from client data directly.
 * Fail-closed: tenant-scoped work without a resolved context throws.
 *
 * <p>A separate provisional database scope is available only while the filter performs the
 * membership lookup. It can arm PostgreSQL RLS for that lookup, but it is deliberately not returned
 * by {@link #get()} and cannot satisfy any authorization method.
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
    private static final ThreadLocal<String> PROVISIONAL_DATABASE_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    /** Set the fully authorized tenant context and discard any provisional lookup scope. */
    public static void set(Current current) {
        PROVISIONAL_DATABASE_TENANT.remove();
        CURRENT.set(current);
    }

    /**
     * Arm only the database tenant used to verify membership. This is not an authorization grant:
     * callers must not use it as a {@link Current} context.
     */
    public static void setProvisionalDatabaseTenant(String libraryId) {
        if (libraryId == null || libraryId.isBlank()) {
            throw new IllegalArgumentException("Provisional tenant id must not be blank");
        }
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Cannot establish provisional tenant scope over an authorized context");
        }
        PROVISIONAL_DATABASE_TENANT.set(libraryId);
    }

    public static Current get() { return CURRENT.get(); }

    /** Tenant id available only to the datasource when arming the transaction-local RLS setting. */
    static String databaseTenantId() {
        Current current = CURRENT.get();
        return current != null ? current.libraryId() : PROVISIONAL_DATABASE_TENANT.get();
    }

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

    /** Clear both authorized and provisional state at the end of every request. */
    public static void clear() {
        CURRENT.remove();
        PROVISIONAL_DATABASE_TENANT.remove();
    }
}
