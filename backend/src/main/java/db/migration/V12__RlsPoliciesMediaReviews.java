package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extends the RLS backstop (isolation layer 6 of 9) to Phase-7 media & review tables. No-op on H2. */
public class V12__RlsPoliciesMediaReviews extends BaseJavaMigration {

    private static final String[] TENANT_TABLES = {
        "media_objects", "book_ratings", "book_reviews", "review_reports", "review_helpful_votes"
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
