package com.jotheca.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-only verification for the V27/V28 discovery schema foundation. */
@Testcontainers(disabledWithoutDocker = true)
class DiscoveryPostgresSchemaTest {

    private static final String MIGRATE_PASSWORD = "discovery-migrate-password";
    private static final String APP_PASSWORD = "discovery-app-password";
    private static final String READALL_PASSWORD = "discovery-readall-password";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static PostgreSQLContainer<?> pgWithoutRuntimeRoles =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        provisionRuntimeRolesAndExtensions();
        flyway(pg, "jotheca_migrate", MIGRATE_PASSWORD).migrate();
        flyway(pgWithoutRuntimeRoles, pgWithoutRuntimeRoles.getUsername(),
                pgWithoutRuntimeRoles.getPassword()).migrate();

        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO libraries
                        (id, uid, slug, name, type, status, join_policy, visibility,
                         member_count, created_at, updated_at)
                    VALUES ('lib-discovery-1', 'LIB-DISC01', 'discovery-one', 'Discovery One',
                            'PUBLIC', 'ACTIVE', 'PUBLIC', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO books
                        (id, tenant_id, slug, title, author, created_at, updated_at)
                    VALUES ('book-discovery-1', 'lib-discovery-1', 'book-one', 'Book One',
                            'Author One', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO discovery.editions
                        (id, edition_key, title, author_display, metadata_fingerprint,
                         created_at, updated_at)
                    VALUES ('edition-discovery-1', 'isbn:9780000000001', 'Book One',
                            'Author One', 'fingerprint-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO discovery.editions
                        (id, edition_key, title, author_display, metadata_fingerprint,
                         created_at, updated_at)
                    VALUES ('edition-discovery-2', 'isbn:9780000000002', 'Book Two',
                            'Author Two', 'fingerprint-2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO discovery.holdings
                        (id, edition_id, library_public_id, library_slug, library_name,
                         catalog_visibility, holdings_visibility, created_at, updated_at)
                    VALUES ('holding-discovery-1', 'edition-discovery-1', 'lib-discovery-1',
                            'discovery-one', 'Discovery One', 'PUBLIC', 'PRESENCE',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);

        }
    }

    @AfterAll
    static void tearDown() throws Exception {
    }

    @Test
    void v27DefaultsChecksAndIndexesExist() throws Exception {
        try (Connection connection = owner()) {
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'libraries'
                      AND column_name = 'catalog_visibility'
                      AND column_default LIKE '%MEMBERS_ONLY%'
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'libraries'
                      AND column_name = 'holdings_visibility'
                      AND column_default LIKE '%PRESENCE%'
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'books'
                      AND column_name = 'global_discoverable'
                      AND column_default LIKE '%false%'
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conname IN ('chk_libraries_catalog_visibility',
                                      'chk_libraries_holdings_visibility')
                    """)).isEqualTo(2);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname IN ('idx_libraries_global_discovery',
                                        'idx_books_global_discovery')
                    """)).isEqualTo(2);
        }
    }

    @Test
    void flywayReachesV28AndTextEmbeddingContractRemainsIntact() throws Exception {
        try (Connection connection = owner()) {
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '28' AND success
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'book_embeddings'
                      AND column_name = 'embedding' AND data_type = 'text'
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname = 'public' AND indexname = 'idx_book_embeddings_hnsw'
                    """)).isEqualTo(0);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void v28CatalogMetadataIsPresent() throws Exception {
        try (Connection connection = owner()) {
            assertThat(singleInt(connection, "SELECT COUNT(*) FROM pg_namespace WHERE nspname = 'discovery'"))
                    .isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'discovery'
                      AND table_name IN ('editions', 'holdings', 'holding_sources')
                    """)).isEqualTo(3);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE connamespace = 'discovery'::regnamespace
                      AND conname IN ('uq_discovery_editions_key',
                                      'uq_discovery_holding_edition_library',
                                      'uq_discovery_source_identity',
                                      'fk_discovery_source_holding_edition')
                    """)).isEqualTo(4);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname = 'discovery'
                      AND indexname IN ('idx_discovery_editions_search',
                                        'idx_discovery_editions_isbn',
                                        'idx_discovery_holdings_library',
                                        'idx_discovery_holdings_edition',
                                        'idx_discovery_sources_holding',
                                        'idx_discovery_sources_reconciliation')
                    """)).isEqualTo(6);
        }
    }

    @Test
    void duplicateSourceIdentityAndMismatchedEditionAreRejected() throws Exception {
        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO discovery.holding_sources
                        (id, holding_id, edition_id, source_library_id, source_book_id,
                         created_at, updated_at)
                    VALUES ('source-discovery-1', 'holding-discovery-1', 'edition-discovery-1',
                            'lib-discovery-1', 'book-discovery-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO discovery.holding_sources
                        (id, holding_id, edition_id, source_library_id, source_book_id,
                         created_at, updated_at)
                    VALUES ('source-discovery-duplicate', 'holding-discovery-1', 'edition-discovery-1',
                            'lib-discovery-1', 'book-discovery-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO discovery.holding_sources
                        (id, holding_id, edition_id, source_library_id, source_book_id,
                         created_at, updated_at)
                    VALUES ('source-discovery-mismatch', 'holding-discovery-1', 'edition-discovery-2',
                            'lib-discovery-1', 'book-discovery-2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void v26TenantRlsRemainsEnabledAndForced() throws Exception {
        try (Connection connection = owner()) {
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relrowsecurity AND c.relforcerowsecurity
                    """)).isGreaterThanOrEqualTo(29);
        }
    }

    @Test
    void runtimeRolesRemainLeastPrivilegeAfterV26() throws Exception {
        try (Connection connection = owner()) {
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_roles
                    WHERE rolname = 'jotheca_migrate' AND NOT rolsuper AND NOT rolbypassrls
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_roles
                    WHERE rolname = 'jotheca_app' AND NOT rolsuper AND NOT rolbypassrls
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_roles
                    WHERE rolname = 'jotheca_readall' AND NOT rolsuper AND rolbypassrls
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*)
                    WHERE has_table_privilege('jotheca_app', 'books', 'SELECT')
                      AND NOT has_table_privilege('jotheca_app', 'flyway_schema_history', 'INSERT')
                      AND has_table_privilege('jotheca_readall', 'books', 'SELECT')
                      AND NOT has_table_privilege('jotheca_readall', 'books', 'INSERT')
                    """)).isEqualTo(1);
        }
    }

    @Test
    void runtimeConnectionsPreserveApplicationAccessAndHideCredentialsFromReadAll() throws Exception {
        try (Connection app = asApp(); Connection readall = asReadall()) {
            assertThat(singleInt(app, "SELECT COUNT(*) FROM auth_sessions")).isZero();
            assertThat(singleInt(app, "SELECT COUNT(*) FROM verification_tokens")).isZero();
            assertThat(singleInt(app, "SELECT COUNT(*) FROM users WHERE password_hash IS NOT NULL")).isZero();

            assertThatThrownBy(() -> singleInt(readall, "SELECT COUNT(*) FROM auth_sessions"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> singleInt(readall, "SELECT COUNT(*) FROM verification_tokens"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> singleInt(readall,
                    "SELECT COUNT(*) FROM users WHERE password_hash IS NOT NULL"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> singleInt(readall,
                    "SELECT COUNT(*) FROM users WHERE mfa_secret IS NOT NULL"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> singleInt(readall,
                    "SELECT COUNT(*) FROM users WHERE mfa_enabled"))
                    .isInstanceOf(SQLException.class);
            assertThat(singleInt(readall, "SELECT COUNT(id) FROM users")).isZero();
        }
    }

    @Test
    void missingRuntimeRolesDoNotAbortFlywayTransaction() throws Exception {
        try (Connection connection = absentOwner()) {
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '26' AND success
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '28' AND success
                    """)).isEqualTo(1);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*) FROM pg_roles
                    WHERE rolname IN ('jotheca_migrate', 'jotheca_app', 'jotheca_readall')
                    """)).isEqualTo(0);
        }
    }

    private static void provisionRuntimeRolesAndExtensions() throws SQLException {
        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            statement.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_migrate') THEN
                        CREATE ROLE jotheca_migrate LOGIN;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_app') THEN
                        CREATE ROLE jotheca_app LOGIN;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_readall') THEN
                        CREATE ROLE jotheca_readall LOGIN;
                      END IF;
                    END $$
                    """);
            statement.execute("ALTER ROLE jotheca_migrate WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"
                    + MIGRATE_PASSWORD + "'");
            statement.execute("ALTER ROLE jotheca_app WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"
                    + APP_PASSWORD + "'");
            statement.execute("ALTER ROLE jotheca_readall WITH LOGIN NOSUPERUSER BYPASSRLS PASSWORD '"
                    + READALL_PASSWORD + "'");
            statement.execute("ALTER DATABASE \"" + pg.getDatabaseName() + "\" OWNER TO jotheca_migrate");
            statement.execute("ALTER SCHEMA public OWNER TO jotheca_migrate");
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> container, String username, String password) {
        return Flyway.configure()
                .dataSource(container.getJdbcUrl(), username, password)
                .locations("classpath:db/migration")
                .load();
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static Connection absentOwner() throws SQLException {
        return DriverManager.getConnection(pgWithoutRuntimeRoles.getJdbcUrl(),
                pgWithoutRuntimeRoles.getUsername(), pgWithoutRuntimeRoles.getPassword());
    }

    private static Connection asApp() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "jotheca_app", APP_PASSWORD);
    }

    private static Connection asReadall() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "jotheca_readall", READALL_PASSWORD);
    }

    private static int singleInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
