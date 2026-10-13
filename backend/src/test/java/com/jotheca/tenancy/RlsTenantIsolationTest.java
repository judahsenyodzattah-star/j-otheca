package com.jotheca.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL ROW-LEVEL-SECURITY integration test — the DATABASE-layer half of the tenant-isolation
 * remediation (audit 2026-10-11, see docs/audits/PHASE-A-REMEDIATION-DESIGN-2026-10-11.md).
 *
 * <p>Unlike {@link TenantIsolationTest} (app-layer guards on H2), this boots a real Postgres and
 * exercises the exact fail-closed policy shape from {@code V26__RlsHardening} AS the least-privilege
 * runtime role {@code jotheca_app} (NOSUPERUSER, NOBYPASSRLS, non-owner). It proves the policy actually
 * confines a non-owner role — which the previous owner/superuser connection silently bypassed.
 *
 * <p>Requires Docker (Testcontainers). Runs in CI; cannot run in a Docker-less sandbox.
 *
 * <p>Covers (per remediation acceptance): cross-tenant read denial, cross-tenant WRITE + unauthorized
 * reassignment denial (WITH CHECK), missing tenant context fail-closed, connection-pool leakage
 * (SET LOCAL must not survive a transaction boundary), and the audited read-only platform role.
 */
@Testcontainers
class RlsTenantIsolationTest {

    // Ephemeral, throwaway credentials for the disposable container only — never real secrets.
    private static final String APP_PW = "app_probe_pw";
    private static final String READALL_PW = "readall_probe_pw";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @BeforeAll
    static void provisionAndSeed() throws Exception {
        try (Connection owner = owner(); Statement st = owner.createStatement()) {
            st.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='jotheca_app') THEN
                    CREATE ROLE jotheca_app LOGIN PASSWORD '%s' NOSUPERUSER NOBYPASSRLS;
                  END IF;
                END $$;
                """.formatted(APP_PW));
            st.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='jotheca_readall') THEN
                    CREATE ROLE jotheca_readall LOGIN PASSWORD '%s' NOSUPERUSER BYPASSRLS;
                  END IF;
                END $$;
                """.formatted(READALL_PW));

            st.execute("CREATE TABLE rls_probe (id bigserial PRIMARY KEY, tenant_id text NOT NULL, note text)");
            // Least-privilege grants (mirrors V26 grantLeastPrivilege).
            st.execute("GRANT USAGE, SELECT ON SEQUENCE rls_probe_id_seq TO jotheca_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON rls_probe TO jotheca_app");
            st.execute("GRANT SELECT ON rls_probe TO jotheca_readall");
            // Fail-closed policy — identical shape to V26.
            st.execute("ALTER TABLE rls_probe ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE rls_probe FORCE ROW LEVEL SECURITY");
            st.execute("""
                CREATE POLICY rls_probe_tenant_isolation ON rls_probe
                USING (tenant_id = current_setting('app.tenant_id', true))
                WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
                """);
            // Seed as owner (owner bypasses RLS): 2 rows in tenant A, 1 row in tenant B.
            st.execute("INSERT INTO rls_probe(tenant_id, note) VALUES ('A','A-1'),('A','A-2'),('B','B-1')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (Connection owner = owner(); Statement st = owner.createStatement()) {
            st.execute("DROP TABLE IF EXISTS rls_probe");
            st.execute("DROP TABLE IF EXISTS rls_audit");
        }
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static Connection asApp() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "jotheca_app", APP_PW);
    }

    private static Connection asReadall() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "jotheca_readall", READALL_PW);
    }

    /** Open a transaction (so SET LOCAL applies) and arm the tenant GUC, mirroring TenantAwareDataSource. */
    private static void beginTxAsTenant(Connection c, String tenant) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
        }
    }

    private static int countRows(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM rls_probe")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countRowsForTenant(Connection c, String tenant) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM rls_probe WHERE tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("cross-tenant READ is denied: tenant A sees only A rows, never B")
    void crossTenantReadDenied() throws Exception {
        try (Connection app = asApp()) {
            beginTxAsTenant(app, "A");
            assertThat(countRows(app)).isEqualTo(2);
            assertThat(countRowsForTenant(app, "A")).isEqualTo(2);
            assertThat(countRowsForTenant(app, "B")).isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("missing tenant context fails closed: no GUC => zero rows, not every row")
    void missingTenantContextFailsClosed() throws Exception {
        try (Connection app = asApp()) {
            app.setAutoCommit(false); // transaction started, but NO SET LOCAL — simulates unresolved tenant
            assertThat(countRows(app)).isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("own-tenant write is allowed")
    void ownTenantWriteAllowed() throws Exception {
        try (Connection app = asApp()) {
            beginTxAsTenant(app, "A");
            try (Statement st = app.createStatement()) {
                st.executeUpdate("INSERT INTO rls_probe(tenant_id, note) VALUES ('A','A-3')");
            }
            app.commit();
            try (Connection owner = owner(); Statement st = owner.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM rls_probe WHERE note='A-3'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("cross-tenant WRITE is blocked by WITH CHECK (cannot plant a row under another tenant)")
    void crossTenantWriteBlocked() throws Exception {
        try (Connection app = asApp()) {
            beginTxAsTenant(app, "A");
            assertThatThrownBy(() -> {
                try (Statement st = app.createStatement()) {
                    st.executeUpdate("INSERT INTO rls_probe(tenant_id, note) VALUES ('B','planted')");
                }
            }).isInstanceOf(SQLException.class); // RLS WITH CHECK violation
            app.rollback();
            // And nothing was written:
            try (Connection owner = owner(); Statement st = owner.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM rls_probe WHERE note='planted'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("unauthorized REASSIGNMENT is blocked: cannot move an own row to another tenant")
    void unauthorizedReassignmentBlocked() throws Exception {
        try (Connection app = asApp()) {
            beginTxAsTenant(app, "A");
            assertThatThrownBy(() -> {
                try (Statement st = app.createStatement()) {
                    st.executeUpdate("UPDATE rls_probe SET tenant_id = 'B' WHERE note = 'A-1'");
                }
            }).isInstanceOf(SQLException.class); // WITH CHECK blocks the reassignment
            app.rollback();
        }
    }

    @Test
    @DisplayName("connection-pool leakage: SET LOCAL is transaction-scoped and does not survive commit")
    void noTenantLeakAcrossPooledTransactions() throws Exception {
        try (Connection app = asApp()) {
            // tx1 as A on this (pooled) connection
            beginTxAsTenant(app, "A");
            assertThat(countRowsForTenant(app, "A")).isEqualTo(2);
            app.commit(); // SET LOCAL cleared at commit

            // tx2 on the SAME connection with NO tenant armed => must fail closed (0 rows), proving no leak
            app.setAutoCommit(false);
            assertThat(countRows(app)).isZero();
            app.rollback();

            // tx3 as B on the same connection => sees only B, still no A leakage
            beginTxAsTenant(app, "B");
            assertThat(countRows(app)).isEqualTo(1);
            assertThat(countRowsForTenant(app, "A")).isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("platform role (jotheca_readall) reads across tenants but is SELECT-only")
    void platformRoleIsReadOnlyCrossTenant() throws Exception {
        try (Connection readall = asReadall()) {
            readall.setAutoCommit(false); // BYPASSRLS: no GUC needed, sees every tenant
            assertThat(countRows(readall)).isEqualTo(3);
            readall.rollback();

            assertThatThrownBy(() -> {
                try (Statement st = readall.createStatement()) {
                    st.executeUpdate("INSERT INTO rls_probe(tenant_id, note) VALUES ('A','x')");
                }
            }).isInstanceOf(SQLException.class); // no INSERT privilege granted to the platform role
        }
    }

    @Test
    @DisplayName("platform-write role (jotheca_app) is confined to the armed target library — no cross-tenant write")
    void platformWriteRoleConfinedToArmedLibrary() throws Exception {
        try (Connection app = asApp()) {
            // A platform WRITE (plan change / onboarding approval) runs as jotheca_app with the tenant GUC
            // armed to the target library A. It may touch A but MUST NOT see or mutate B — proving there is
            // no cross-tenant write role and that the tenant-facing role can never reach read-all.
            beginTxAsTenant(app, "A");
            assertThat(countRowsForTenant(app, "A")).isEqualTo(2);
            assertThat(countRowsForTenant(app, "B")).isZero();
            try (Statement st = app.createStatement()) {
                st.executeUpdate("INSERT INTO rls_probe(tenant_id, note) VALUES ('A','platform-write')");
            }
            assertThatThrownBy(() -> {
                try (Statement st = app.createStatement()) {
                    st.executeUpdate("INSERT INTO rls_probe(tenant_id, note) VALUES ('B','platform-write')");
                }
            }).isInstanceOf(SQLException.class); // WITH CHECK blocks writing into another tenant
            app.rollback();
        }
    }
}
