package com.jotheca.common.tenant;

/**
 * Which database "plane" the current request must use (Phase A tenant-isolation remediation). Read by
 * {@link com.jotheca.common.datasource.PlaneRoutingDataSource} to pick the connection's role:
 * TENANT (jotheca_app, RLS-confined — also serves platform WRITES, confined to the armed target library)
 * and PLATFORM_READ (jotheca_readall, read-only, BYPASSRLS — platform-plane GETs only).
 *
 * <p>Least privilege by design: there is NO cross-tenant WRITE role. Platform writes run as jotheca_app
 * with the tenant context armed to the single target library (see PlatformWriteContext), so they are
 * RLS-confined exactly like tenant traffic. Only active when app.datasource.restricted-role=true;
 * otherwise a single owner-role datasource is used and this is inert. Always cleared after the request.
 */
public final class PlaneContext {

    public static final String TENANT = "tenant";
    public static final String PLATFORM_READ = "platformRead";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PlaneContext() {}

    public static void set(String plane) {
        CURRENT.set(plane);
    }

    /** Current plane for routing; defaults to TENANT when unset (fail-safe: never the read-all plane). */
    public static String get() {
        String p = CURRENT.get();
        return p == null ? TENANT : p;
    }

    /** Raw value (may be null) — for audit decisions that must distinguish "unset" from TENANT. */
    public static String peek() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
