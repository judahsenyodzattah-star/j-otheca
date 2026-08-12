package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extends the RLS backstop (isolation layer 6 of 9) to the Phase-5 circulation tables. No-op on H2. */
public class V8__RlsPoliciesCirculation extends BaseJavaMigration {

    private static final String[] TENANT_TABLES = {
        "loan_policies", "loans", "reservations", "fines", "notifications"
    };

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        if (!product.contains("postgres")) {
            return; // H2 dev/test
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
