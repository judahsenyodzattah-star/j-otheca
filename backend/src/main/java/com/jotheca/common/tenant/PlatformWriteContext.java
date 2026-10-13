package com.jotheca.common.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Arms the tenant context for a PLATFORM-plane WRITE that runs as {@code jotheca_app}, so RLS confines it
 * to the single target library — the least-privilege alternative to a BYPASSRLS cross-tenant write role.
 *
 * <p>No-op when {@code app.datasource.restricted-role=false} (the owner role is used and RLS is bypassed),
 * so default behavior is unchanged. Under the cutover the write can touch ONLY the armed library's rows.
 */
@Component
public class PlatformWriteContext {

    private final boolean restrictedRole;

    /**
     * Shared, transaction-scoped EntityManager proxy. Arming the GUC through it guarantees the
     * {@code set_config} runs on the SAME connection the following tenant-scoped inserts use — a separate
     * {@code DataSourceUtils.getConnection()} is NOT guaranteed to be the JPA transaction's connection.
     */
    @PersistenceContext
    private EntityManager entityManager;

    public PlatformWriteContext(@Value("${app.datasource.restricted-role:false}") boolean restrictedRole) {
        this.restrictedRole = restrictedRole;
    }

    /**
     * Arm the tenant context BEFORE a {@code @Transactional} write whose target library id is already
     * known (e.g. plan change). {@code TenantAwareDataSource} then arms the GUC at transaction start.
     */
    public void beginForKnownLibrary(String libraryId, String libraryUid) {
        if (restrictedRole) {
            TenantContext.set(new TenantContext.Current(libraryId, libraryUid, null, null));
        }
    }

    /**
     * Arm the tenant context MID-transaction, after the target library row has just been created (e.g.
     * onboarding approval): sets {@code TenantContext} and (re)arms the transaction-local GUC on the
     * current connection — via the JPA {@link EntityManager}, so it is the exact connection the subsequent
     * {@code memberships}/{@code subscriptions} inserts run on — confining them to that one library.
     */
    public void armForNewLibrary(String libraryId, String libraryUid) {
        if (!restrictedRole) {
            return;
        }
        TenantContext.set(new TenantContext.Current(libraryId, libraryUid, null, null));
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tenantId, true)")
                .setParameter("tenantId", libraryId)
                .getSingleResult();
    }

    public void clear() {
        TenantContext.clear();
    }
}
