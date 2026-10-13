package com.jotheca.common.datasource;

import com.jotheca.common.tenant.PlaneContext;
import db.migration.V26__RlsHardening;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase A startup guard — refuses to boot when {@code app.datasource.restricted-role=true} but the
 * database is not actually enforcing tenant isolation.
 *
 * <p>Why this exists. {@code V26__RlsHardening} applies and self-verifies the RLS configuration, but
 * it runs once. Three things can still go wrong afterwards, and each of them is otherwise invisible
 * until data leaks:
 * <ul>
 *   <li>the app is pointed at a role that CAN bypass RLS (a superuser, or the table owner, or a role
 *       with {@code BYPASSRLS}) — the policies exist but are never applied;</li>
 *   <li>a required runtime role was never provisioned ({@code infra/db/provision-roles.sh} not run),
 *       so the app either cannot connect or connects with far more privilege than intended;</li>
 *   <li>a LATER migration adds a tenant-owned table without a policy (or without FORCE RLS), leaving
 *       that table fail-open. Nothing in the migration chain would notice.</li>
 * </ul>
 * This guard turns each of those into a hard startup failure with an actionable message.
 *
 * <p><b>No hardcoded table list.</b> The expected set of tenant tables is DERIVED from the catalog
 * (every ordinary or partitioned table in {@code public} that has a {@code tenant_id} column), so it
 * can never go stale and automatically covers tables added by future migrations. That is what makes
 * it a real coverage guard rather than a copy of V26's list. Because derivation alone cannot prove
 * that nothing was OMITTED, the derived set is additionally cross-checked against V26's documented
 * inventory ({@link V26__RlsHardening#tenantTables()}): a documented table that discovery did not
 * find is a hard failure, and a discovered table missing from the inventory is reported as drift.
 *
 * <p>Active ONLY under {@code restricted-role=true}. With the default (false) the app deliberately
 * connects as the owner and RLS is bypassed, so these checks would be wrong there.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.datasource.restricted-role", havingValue = "true")
public class RlsStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RlsStartupGuard.class);

    /** Roles that must exist before the restricted-role cutover can work. */
    private static final String[] REQUIRED_ROLES = {"jotheca_app", "jotheca_readall"};

    private final DataSource tenantDataSource;

    public RlsStartupGuard(@Qualifier("tenantDataSource") DataSource tenantDataSource) {
        this.tenantDataSource = tenantDataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // The plane defaults to TENANT, so a plain getConnection() here is the tenant pool
        // (jotheca_app) — exactly the role whose confinement we must prove.
        PlaneContext.set(PlaneContext.TENANT);
        try (java.sql.Connection c = tenantDataSource.getConnection()) {
            verify(c);
        } finally {
            PlaneContext.clear();
        }
    }

    private void verify(java.sql.Connection c) throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> drift = new ArrayList<>();

        // ---- 1. the runtime role itself must be RLS-confined --------------------------------
        String currentUser;
        boolean superuser;
        boolean bypassRls;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            if (!rs.next()) {
                throw new IllegalStateException(
                        "RLS startup guard: cannot resolve current_user in pg_roles — refusing to start.");
            }
            currentUser = rs.getString(1);
            superuser = rs.getBoolean(2);
            bypassRls = rs.getBoolean(3);
        }
        if (superuser) {
            problems.add("the runtime role '" + currentUser + "' is SUPERUSER — RLS is bypassed entirely. "
                    + "Point SPRING_DATASOURCE_USERNAME at jotheca_app.");
        }
        if (bypassRls) {
            problems.add("the runtime role '" + currentUser + "' has BYPASSRLS — RLS policies would never "
                    + "apply. Use jotheca_app (NOSUPERUSER, NOBYPASSRLS).");
        }

        // ---- 1b. the runtime role must not INHERIT superuser / BYPASSRLS from a group role -------
        // rolsuper and rolbypassrls are attributes of the role ITSELF and are not inherited, but the
        // effective privileges are: a member of a superuser role can do anything, and a member of a
        // BYPASSRLS role is not confined by RLS. Reading current_user's own flags therefore proves
        // nothing unless its memberships are clean too. pg_has_role resolves membership directly.
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT r.rolname, r.rolsuper, r.rolbypassrls
                     FROM pg_roles r
                     WHERE (r.rolsuper OR r.rolbypassrls)
                       AND (pg_has_role(current_user, r.rolname, 'USAGE')
                            OR pg_has_role('jotheca_app', r.rolname, 'USAGE'))
                     """)) {
            while (rs.next()) {
                problems.add("the runtime role inherits from '" + rs.getString(1) + "', which is "
                        + (rs.getBoolean(2) ? "SUPERUSER" : "BYPASSRLS") + " - RLS would be bypassed "
                        + "INDIRECTLY through role membership. REVOKE " + rs.getString(1)
                        + " FROM the runtime role.");
            }
        }

        // ---- 2. required roles provisioned ---------------------------------------------------
        try (Statement st = c.createStatement()) {
            for (String role : REQUIRED_ROLES) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
                    if (!rs.next()) {
                        problems.add("required role '" + role + "' does not exist — run "
                                + "infra/db/provision-roles.sh before enabling restricted-role.");
                    }
                }
            }
        }

        // ---- 3. every tenant-owned table must be fail-closed (derived, never stale) ----------
        Map<String, TableState> tenants = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                     FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                       AND EXISTS (SELECT 1 FROM pg_attribute a
                                   WHERE a.attrelid = c.oid AND a.attname = 'tenant_id'
                                     AND a.attnum > 0 AND NOT a.attisdropped)
                     ORDER BY c.relname
                     """)) {
            while (rs.next()) {
                tenants.put(rs.getString(1), new TableState(rs.getBoolean(2), rs.getBoolean(3)));
            }
        }
        if (tenants.isEmpty()) {
            problems.add("no tenant-owned table found in schema public — either the schema is empty or "
                    + "the guard is pointed at the wrong database.");
        }

        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT tablename, policyname, permissive, cmd, qual, with_check
                     FROM pg_policies WHERE schemaname = 'public'
                     """)) {
            while (rs.next()) {
                TableState state = tenants.get(rs.getString(1));
                if (state == null) {
                    continue; // a policy on a non-tenant table is not this guard's concern
                }
                if (!(rs.getString(1) + "_tenant_isolation").equals(rs.getString(2))) {
                    continue; // some other policy; only the tenant policy is asserted here
                }
                state.policyPresent = true;
                state.permissive = "PERMISSIVE".equals(rs.getString(3));
                state.cmdAll = "ALL".equals(rs.getString(4));
                state.qual = rs.getString(5);
                state.withCheck = rs.getString(6);
            }
        }

        // ---- 3b. cross-check the derived set against V26's documented inventory -----------------
        // Discovery answers "is everything I found protected?". On its own it CANNOT answer "did I
        // find everything?" - a table that is missing, renamed, or of a relkind the query does not
        // cover would simply be absent, and every assertion above would still pass. So the derived
        // set is also compared with the authoritative inventory installed by V26__RlsHardening.
        List<String> documented = V26__RlsHardening.tenantTables();
        for (String table : documented) {
            if (!tenants.containsKey(table)) {
                problems.add(table + ": a documented tenant table (V26 TENANT_TABLES) was NOT discovered "
                        + "in schema public - it is missing, renamed, or of a relkind discovery does not "
                        + "cover. Tenant isolation for it is UNPROVEN, not proven.");
            }
        }
        for (String table : tenants.keySet()) {
            if (!documented.contains(table)) {
                // Discovered AND protected, but absent from the inventory: a real drift signal. Not a
                // failure (isolation holds), but the inventory must be updated or the next person to
                // read TENANT_TABLES will be working from an incomplete list.
                drift.add(table);
            }
        }

        for (Map.Entry<String, TableState> e : tenants.entrySet()) {
            String table = e.getKey();
            TableState s = e.getValue();
            if (!s.enabled) {
                problems.add(table + ": ROW LEVEL SECURITY is not ENABLED");
            }
            if (!s.forced) {
                problems.add(table + ": ROW LEVEL SECURITY is not FORCED — the table owner would bypass it. "
                        + "Run ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY.");
            }
            if (!s.policyPresent) {
                problems.add(table + ": no '<table>_tenant_isolation' policy — rows would be unconfined. "
                        + "Re-run V26__RlsHardening.");
            }
            if (!s.permissive) {
                problems.add(table + ": the tenant_isolation policy is not PERMISSIVE — it would combine "
                        + "as a RESTRICTIVE predicate and could deny legitimate access");
            }
            if (!s.cmdAll) {
                problems.add(table + ": the tenant_isolation policy is not FOR ALL — writes would escape it");
            }
            if (s.qual == null || s.qual.isBlank()) {
                problems.add(table + ": no USING predicate on the tenant_isolation policy (reads unconfined)");
            } else if (!s.qual.contains("current_setting('app.tenant_id'")) {
                problems.add(table + ": USING does not reference the tenant GUC: " + s.qual);
            }
            if (s.withCheck == null || s.withCheck.isBlank()) {
                problems.add(table + ": no WITH CHECK predicate — cross-tenant writes would be allowed");
            } else if (!s.withCheck.contains("current_setting('app.tenant_id'")) {
                problems.add(table + ": WITH CHECK does not reference the tenant GUC: " + s.withCheck);
            }
        }

        // ---- 4. the platform read role must be read-only and credential-blind -----------------
        try (Statement st = c.createStatement()) {
            // Schema-wide, not one representative table: a single stray GRANT on any other table would
            // otherwise leave the "SELECT-only" claim unproven. has_table_privilege evaluates the
            // EFFECTIVE privilege - it already accounts for grants inherited via role membership and
            // via PUBLIC, and for object ownership - which is exactly what has to be checked here.
            try (ResultSet rs = st.executeQuery("""
                    SELECT c.relname, p.priv
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')) AS p(priv)
                    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                      AND has_table_privilege('jotheca_readall', c.oid, p.priv)
                    ORDER BY 1, 2
                    """)) {
                while (rs.next()) {
                    problems.add("jotheca_readall can " + rs.getString(2) + " " + rs.getString(1)
                            + " — the platform read role must be SELECT-only");
                }
            }
            if (privilege(st, "jotheca_readall", "auth_sessions", "SELECT")) {
                problems.add("jotheca_readall can read auth_sessions (refresh-token hashes)");
            }
            if (privilege(st, "jotheca_readall", "verification_tokens", "SELECT")) {
                problems.add("jotheca_readall can read verification_tokens (email/reset token hashes)");
            }
            if (privilege(st, "jotheca_readall", "users", "SELECT")) {
                problems.add("jotheca_readall has table-level SELECT on users — it must be column-level "
                        + "so password_hash / mfa_secret stay unreachable");
            }
            if (columnPrivilege(st, "jotheca_readall", "users", "password_hash")) {
                problems.add("jotheca_readall can read users.password_hash");
            }
            if (columnPrivilege(st, "jotheca_readall", "users", "mfa_secret")) {
                problems.add("jotheca_readall can read users.mfa_secret");
            }
            for (String priv : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                if (privilege(st, "jotheca_app", "flyway_schema_history", priv)) {
                    problems.add("jotheca_app can " + priv + " flyway_schema_history — migration history "
                            + "must not be writable by a runtime role");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("""
                    Refusing to start: app.datasource.restricted-role=true but the database does not \
                    enforce tenant isolation. %d problem(s):
                      - %s

                    Fix these before enabling the restricted-role cutover. The default \
                    (restricted-role=false) keeps the previous single-owner-role behaviour.""",
                    problems.size(), String.join("\n                      - ", problems));
        }
        if (drift.isEmpty()) {
            log.info("RLS startup guard passed: runtime role '{}' is RLS-confined; {} tenant-owned tables "
                    + "are fail-closed (ENABLE+FORCE RLS, USING + WITH CHECK on the tenant GUC) and match "
                    + "V26's documented inventory; jotheca_readall is SELECT-only across the whole schema "
                    + "and cannot reach credential material.", currentUser, tenants.size());
        } else {
            log.warn("RLS startup guard passed, but {} tenant-owned table(s) are protected yet MISSING "
                    + "from V26's documented inventory (TENANT_TABLES): {}. Isolation holds, but add them "
                    + "to the inventory so it stays the single source of truth.", drift.size(), drift);
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private static final class TableState {
        final boolean enabled;
        final boolean forced;
        boolean policyPresent;
        boolean permissive;
        boolean cmdAll;
        String qual;
        String withCheck;

        TableState(boolean enabled, boolean forced) {
            this.enabled = enabled;
            this.forced = forced;
        }
    }

    private static boolean privilege(Statement st, String role, String table, String privilege)
            throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT has_table_privilege('" + role + "', '" + table
                + "', '" + privilege + "')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static boolean columnPrivilege(Statement st, String role, String table, String column)
            throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT has_column_privilege('" + role + "', '" + table
                + "', '" + column + "', 'SELECT')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
