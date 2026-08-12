package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Tenant isolation layer 6 of 9: PostgreSQL Row-Level Security.
 *
 * Applies only on real PostgreSQL. On H2 (dev/tests) it is a no-op —
 * the service-layer TenantGuard + Hibernate tenant filter remain active everywhere,
 * and are what the TenantIsolationTest suite verifies. RLS is the backstop that
 * makes even a raw SQL bug unable to read another tenant's rows.
 *
 * Behaviour: when the session variable {@code app.tenant_id} is set (per-transaction
 * by TenantIsolationAspect on Postgres), only rows of that tenant are visible.
 * When unset (platform plane, seeding), nothing is filtered.
 */
public class V2__RlsPolicies extends BaseJavaMigration {

    private static final String[] TENANT_TABLES = { "memberships", "invitations", "audit_logs" };

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        if (!product.contains("postgres")) {
            return; // H2 dev/test — RLS layer intentionally inactive here
        }
        try (Statement st = context.getConnection().createStatement()) {
            for (String table : TENANT_TABLES) {
                st.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                st.execute("DROP POLICY IF EXISTS " + table + "_tenant_isolation ON " + table);
                st.execute("""
                        CREATE POLICY %s_tenant_isolation ON %s
                        USING (
                            current_setting('app.tenant_id', true) IS NULL
                            OR current_setting('app.tenant_id', true) = ''
                            OR tenant_id = current_setting('app.tenant_id', true)
                        )
                        """.formatted(table, table));
            }
        }
    }
}
