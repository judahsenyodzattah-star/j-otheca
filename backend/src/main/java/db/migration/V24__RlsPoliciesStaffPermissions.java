package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PG-only RLS parity for the staff-permission table (no-op on H2). */
public class V24__RlsPoliciesStaffPermissions extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        if (!product.contains("postgres")) {
            return; // H2 dev/test
        }
        try (Statement st = context.getConnection().createStatement()) {
            st.execute("ALTER TABLE staff_permissions ENABLE ROW LEVEL SECURITY");
            st.execute("DROP POLICY IF EXISTS staff_permissions_tenant_isolation ON staff_permissions");
            st.execute("""
                    CREATE POLICY staff_permissions_tenant_isolation ON staff_permissions
                    USING (
                        current_setting('app.tenant_id', true) IS NULL
                        OR current_setting('app.tenant_id', true) = ''
                        OR tenant_id = current_setting('app.tenant_id', true)
                    )
                    """);
        }
    }
}
