package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * PG-only search accelerators (no-op on H2, mirroring the RLS/vector migrations):
 * pg_trgm GIN indexes make the catalog's `lower(col) LIKE '%q%'` member search index-assisted
 * on real Postgres. On H2 the query stays a simple scan — tests exercise semantics, not the
 * execution plan.
 */
public class V19__TrigramSearchIndexes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        if (!product.contains("postgres")) {
            return; // H2 dev/test
        }
        try (Statement st = context.getConnection().createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            // member catalog search ORs across these columns (CatalogService.search)
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_books_title_trgm
                    ON books USING gin ((lower(title)) gin_trgm_ops)
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_books_author_trgm
                    ON books USING gin ((lower(author)) gin_trgm_ops)
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_books_subjects_trgm
                    ON books USING gin ((lower(subjects)) gin_trgm_ops)
                    """);
            // anonymous library discovery search
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_libraries_name_trgm
                    ON libraries USING gin ((lower(name)) gin_trgm_ops)
                    """);
        }
    }
}
