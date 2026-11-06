package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * PG-only hardening for the AI tables (no-op on H2, mirroring previous RLS migrations):
 *  - RLS tenant policies on the three new tables (isolation layer 6 backstop);
 *  - pgvector extension for future dimensioned-vector projections.
 *    Embeddings remain portable TEXT because local and OpenAI providers may use different
 *    dimensions. PostgreSQL cannot build one valid HNSW index over a dimensionless cast.
 */
public class V17__RlsPoliciesAi extends BaseJavaMigration {

    private static final String[] TENANT_TABLES = {
        "book_embeddings", "ai_usage_counters", "search_events"
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
            // Keep the extension available without creating an invalid dimensionless HNSW
            // index. The embedding column is portable TEXT and provider dimensions vary.
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }
}
