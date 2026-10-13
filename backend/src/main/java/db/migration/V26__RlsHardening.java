package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant-isolation hardening (audit 2026-10-11). Replaces the fail-open V2..V24 RLS policies with
 * FAIL-CLOSED, WITH-CHECK-enforced, FORCED policies so a dedicated least-privilege runtime role
 * ({@code jotheca_app}, {@code NOBYPASSRLS}, non-owner) is actually confined to its own tenant on
 * read AND write, and so an unset tenant context exposes zero rows instead of every row.
 *
 * <p>Why: the app previously connected as the table owner/superuser, for which PostgreSQL bypasses
 * RLS entirely; the old policies were also fail-open ({@code app.tenant_id IS NULL OR ='' OR match})
 * and had no {@code WITH CHECK}. See docs/audits/PHASE-A-REMEDIATION-DESIGN-2026-10-11.md.
 *
 * <p>PostgreSQL-only; no-op on H2 (dev/tests), where the app-layer TenantGuard + Hibernate
 * tenantFilter remain the boundary. NON-DESTRUCTIVE and idempotent: only ENABLE/FORCE RLS,
 * DROP+CREATE POLICY, GRANT and REVOKE - no table or data changes.
 *
 * <p><b>Least privilege.</b> Two runtime roles only, and neither gets more than it needs:
 * <ul>
 *   <li>{@code jotheca_app} — tenant plane: SELECT/INSERT/UPDATE/DELETE, RLS-confined. It also
 *       serves platform-plane WRITES, confined to the single armed target library.</li>
 *   <li>{@code jotheca_readall} — platform READS: SELECT, BYPASSRLS. It is explicitly denied the
 *       credential tables ({@code auth_sessions}, {@code verification_tokens}) and gets
 *       column-level access to {@code users} so {@code password_hash} / {@code mfa_secret} /
 *       {@code mfa_enabled} are unreachable even though the role is BYPASSRLS.</li>
 * </ul>
 * There is deliberately NO cross-tenant WRITE role.
 *
 * <p><b>Self-verification.</b> After applying the changes the migration re-reads the catalog and
 * FAILS if any expected protection is missing, so a silently incomplete hardening can never be
 * recorded as a successful migration.
 */
public class V26__RlsHardening extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V26__RlsHardening.class);

    /**
     * Every tenant-owned table (has a {@code tenant_id} column). Global/identity tables are excluded:
     * {@code users, libraries, author_profiles (user-global), auth_sessions, verification_tokens,
     * subscription_plans, library_onboarding_requests}. Kept in sync with the RLS inventory in the
     * remediation design doc, and cross-checked at runtime by {@link #verifyHardening} and by
     * {@code RlsStartupGuard}, which compares its catalog-derived set against {@link #tenantTables()}.
     */
    private static final String[] TENANT_TABLES = {
        "memberships", "invitations", "audit_logs", "categories", "branches", "shelf_locations",
        "books", "book_copies", "loan_policies", "loans", "reservations", "fines", "notifications",
        "book_interests", "purchase_requests", "media_objects", "book_ratings", "book_reviews",
        "review_reports", "review_helpful_votes", "storage_usage", "library_subscriptions", "payments",
        "book_embeddings", "ai_usage_counters", "search_events", "issues", "issue_messages",
        "staff_permissions"
    };

    /**
     * Tenant tables whose {@code tenant_id} is NULLABLE — {@code audit_logs} (system/platform events)
     * and {@code payments} (onboarding-time payments before a tenant row exists). They additionally
     * permit a NULL-tenant row ONLY when no tenant is armed — never another tenant's rows, and never a
     * tenant row planted while unarmed (still fail-closed for isolation).
     */
    private static final java.util.Set<String> NULLABLE_TENANT_TABLES =
            java.util.Set.of("audit_logs", "payments");

    /** Tables the platform READ role must never read: they hold refresh / verification token hashes. */
    private static final String[] CREDENTIAL_TABLES = {"auth_sessions", "verification_tokens"};

    /**
     * Columns of {@code users} the platform READ role may see. Everything else is withheld, so
     * {@code password_hash}, {@code mfa_secret} and {@code mfa_enabled} are unreachable even though
     * the role is BYPASSRLS. NOTE: this is a COLUMN-level grant, so the table-level grant is revoked
     * first — a table-level grant is a floor and cannot be carved down by a column-level REVOKE.
     */
    private static final String USERS_SAFE_COLUMNS =
            "id, email, full_name, phone, avatar_ref, global_role, status, email_verified_at, "
            + "created_at, updated_at, created_by, updated_by";

    /** Flyway's own bookkeeping table — never writable by a runtime role. */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /**
     * The documented tenant-table inventory, exposed read-only so {@code RlsStartupGuard} can
     * cross-check its catalog-derived set against it without keeping a second, drift-prone copy.
     * THIS LIST IS THE SINGLE SOURCE OF TRUTH. Adding a tenant-owned table means adding it here.
     */
    public static java.util.List<String> tenantTables() {
        return java.util.List.of(TENANT_TABLES);
    }

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        if (!product.contains("postgres")) {
            return; // H2 dev/test - RLS layer intentionally inactive; app-layer guards remain
        }
        try (Statement st = context.getConnection().createStatement()) {
            grantLeastPrivilege(st);
            for (String table : TENANT_TABLES) {
                st.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                st.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
                st.execute("DROP POLICY IF EXISTS " + table + "_tenant_isolation ON " + table);
                // Fail-closed: unset app.tenant_id -> current_setting(...)=NULL -> tenant_id = NULL -> no rows.
                // WITH CHECK: blocks INSERT/UPDATE that would place a row under another tenant.
                // audit_logs has a NULLABLE tenant_id (system/platform events): allow a NULL-tenant row only
                // when NO tenant is armed, still never another tenant's rows (see predicateFor).
                String predicate = predicateFor(table);
                st.execute("""
                        CREATE POLICY %s_tenant_isolation ON %s
                        USING (%s)
                        WITH CHECK (%s)
                        """.formatted(table, table, predicate, predicate));
            }
        }
        verifyHardening(context.getConnection());
    }

    private static String predicateFor(String table) {
        String guc = "current_setting('app.tenant_id', true)";
        if (NULLABLE_TENANT_TABLES.contains(table)) {
            return "tenant_id = " + guc + " OR (tenant_id IS NULL AND " + guc + " IS NULL)";
        }
        return "tenant_id = " + guc;
    }

    /**
     * Least-privilege grants for the two non-owner runtime roles. The roles themselves (and their
     * passwords) are provisioned by deployment tooling - this migration only grants, referencing them by
     * name (no credentials). {@code jotheca_app} = tenant plane (RLS-enforced; also serves platform WRITES,
     * confined to the single armed target library); {@code jotheca_readall} = platform READS (BYPASSRLS,
     * SELECT-only). Sequences are granted so serial/identity inserts work.
     */
    private void grantLeastPrivilege(Statement st) throws Exception {
        grantDml(st, "jotheca_app");       // tenant plane: full DML, RLS-confined
        grantSelectOnly(st, "jotheca_readall"); // platform reads: read-only
    }

    private void grantDml(Statement st, String role) throws Exception {
        grantRequired(st, role, "GRANT USAGE ON SCHEMA public TO " + role);
        grantRequired(st, role, "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
        grantRequired(st, role, "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
        // Flyway's history is the tenant plane's own audit trail of schema changes: a runtime role that
        // could rewrite it could hide a migration. Withdraw write access.
        revokeRequired(st, role, "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON "
                + FLYWAY_HISTORY_TABLE + " FROM " + role);
    }

    private void grantSelectOnly(Statement st, String role) throws Exception {
        grantRequired(st, role, "GRANT USAGE ON SCHEMA public TO " + role);
        grantRequired(st, role, "GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
        // The platform read role is BYPASSRLS, so a blanket SELECT would hand it every credential hash
        // in the system. Withdraw the two token-hash tables outright, and reduce `users` to the columns
        // the platform plane actually needs (PlatformService.stats counts users; nothing else reads it).
        for (String table : CREDENTIAL_TABLES) {
            revokeRequired(st, role, "REVOKE SELECT ON " + table + " FROM " + role);
        }
        revokeRequired(st, role, "REVOKE SELECT ON users FROM " + role);
        grantRequired(st, role, "GRANT SELECT (" + USERS_SAFE_COLUMNS + ") ON users TO " + role);
    }

    /**
     * A grant that MUST succeed when the role exists. A genuinely-optional absence (role not yet
     * provisioned, SQLState 42704) is skipped with a warning so V26 still applies on any target; ANY
     * other failure - notably 42501 insufficient privilege to grant - is a broken security configuration
     * and FAILS the migration rather than being silently ignored.
     */
    private void grantRequired(Statement st, String role, String grant) throws Exception {
        try {
            st.execute(grant);
        } catch (SQLException e) {
            if (isRoleAbsent(e)) {
                log.warn("Role {} not provisioned yet; skipping grant: {} (run infra/db/provision-roles.sh).",
                        role, grant);
            } else {
                throw new SQLException("Required security grant failed (SQLState " + e.getSQLState() + "): "
                        + grant + " - role privileges incomplete; refusing to continue (req: fail closed).", e);
            }
        }
    }

    /** Same contract as {@link #grantRequired}, for revocations. */
    private void revokeRequired(Statement st, String role, String revoke) throws Exception {
        try {
            st.execute(revoke);
        } catch (SQLException e) {
            if (isRoleAbsent(e)) {
                log.warn("Role {} not provisioned yet; skipping revoke: {} (run infra/db/provision-roles.sh).",
                        role, revoke);
            } else {
                throw new SQLException("Required security revoke failed (SQLState " + e.getSQLState() + "): "
                        + revoke + " - role privileges incomplete; refusing to continue (req: fail closed).", e);
            }
        }
    }

    private static boolean isRoleAbsent(SQLException e) {
        return "42704".equals(e.getSQLState());
    }

    // =================================================================================== verification

    /**
     * Re-reads the catalog after applying the changes and FAILS the migration if any expected protection
     * is missing. Without this, a statement that silently no-oped (or a future change that dropped a
     * policy) would still be recorded as a successful migration.
     *
     * <p>Checks, per expected tenant table: RLS enabled, RLS FORCED, the
     * {@code <table>_tenant_isolation} policy exists as a PERMISSIVE policy for ALL commands, and BOTH
     * {@code USING} and {@code WITH CHECK} are present and reference the tenant GUC. The nullable tables
     * must additionally carry the NULL-aware predicate. Then: no table with a {@code tenant_id} column
     * may lack a policy (the coverage guard that catches a tenant table added by a later migration),
     * and the least-privilege revocations must actually be in force.
     */
    private void verifyHardening(Connection c) throws Exception {
        List<String> problems = new ArrayList<>();

        // ---- 1. per-table: enabled + forced + policy + USING + WITH CHECK -----------------------
        String inList = String.join(",", java.util.Arrays.stream(TENANT_TABLES)
                .map(t -> "'" + t + "'").toList());
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname IN (%s)
                    """.formatted(inList))) {
                Set<String> seen = new LinkedHashSet<>();
                while (rs.next()) {
                    String table = rs.getString(1);
                    seen.add(table);
                    if (!rs.getBoolean(2)) {
                        problems.add(table + ": ROW LEVEL SECURITY is not ENABLED");
                    }
                    if (!rs.getBoolean(3)) {
                        problems.add(table + ": ROW LEVEL SECURITY is not FORCED (the table owner would bypass RLS)");
                    }
                }
                for (String table : TENANT_TABLES) {
                    if (!seen.contains(table)) {
                        problems.add(table + ": table not found in schema public");
                    }
                }
            }

            try (ResultSet rs = st.executeQuery("""
                    SELECT tablename, permissive, cmd, qual, with_check
                    FROM pg_policies
                    WHERE schemaname = 'public' AND tablename IN (%s)
                    """.formatted(inList))) {
                Set<String> seen = new LinkedHashSet<>();
                while (rs.next()) {
                    String table = rs.getString(1);
                    seen.add(table);
                    if (!"PERMISSIVE".equals(rs.getString(2))) {
                        problems.add(table + ": policy is not PERMISSIVE (a RESTRICTIVE policy would stack unexpectedly)");
                    }
                    if (!"ALL".equals(rs.getString(3))) {
                        problems.add(table + ": policy command is " + rs.getString(3) + ", expected ALL");
                    }
                    String qual = rs.getString(4);
                    String check = rs.getString(5);
                    if (qual == null || qual.isBlank()) {
                        problems.add(table + ": policy has no USING predicate (reads would not be confined)");
                    } else if (!qual.contains("current_setting('app.tenant_id'")) {
                        problems.add(table + ": USING predicate does not reference the tenant GUC: " + qual);
                    }
                    if (check == null || check.isBlank()) {
                        problems.add(table + ": policy has no WITH CHECK predicate (cross-tenant writes would be allowed)");
                    } else if (!check.contains("current_setting('app.tenant_id'")) {
                        problems.add(table + ": WITH CHECK predicate does not reference the tenant GUC: " + check);
                    }
                    if (NULLABLE_TENANT_TABLES.contains(table)) {
                        if (qual == null || !qual.contains("IS NULL") || check == null || !check.contains("IS NULL")) {
                            problems.add(table + ": nullable tenant_id table lost its NULL-aware predicate");
                        }
                    }
                }
                for (String table : TENANT_TABLES) {
                    if (!seen.contains(table)) {
                        problems.add(table + ": no tenant_isolation policy found");
                    }
                }
            }

            // ---- 2. coverage guard: no tenant-owned table may be left without a policy -----------
            try (ResultSet rs = st.executeQuery("""
                    SELECT c.relname
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                      AND EXISTS (SELECT 1 FROM pg_attribute a
                                  WHERE a.attrelid = c.oid AND a.attname = 'tenant_id'
                                    AND a.attnum > 0 AND NOT a.attisdropped)
                      AND NOT EXISTS (SELECT 1 FROM pg_policies p
                                      WHERE p.schemaname = 'public' AND p.tablename = c.relname
                                        AND p.policyname = c.relname || '_tenant_isolation')
                    """)) {
                while (rs.next()) {
                    problems.add(rs.getString(1) + ": has a tenant_id column but NO tenant_isolation policy "
                            + "(fail-open — add it to V26's TENANT_TABLES or create a policy in the same "
                            + "migration that adds the table)");
                }
            }

            // ---- 3. least-privilege revocations actually in force --------------------------------
            problems.addAll(verifyRolePrivileges(st));
        }

        if (!problems.isEmpty()) {
            throw new SQLException("V26 post-migration verification FAILED — the tenant-isolation "
                    + "configuration is incomplete. Refusing to record this migration as successful. "
                    + problems.size() + " problem(s):\n  - " + String.join("\n  - ", problems));
        }
        log.info("V26 verification passed: {} tenant tables ENABLE+FORCE RLS with fail-closed "
                + "USING/WITH CHECK policies; no unprotected tenant-owned table; least-privilege "
                + "grants confirmed.", TENANT_TABLES.length);
    }

    /**
     * Confirms the platform READ role really cannot reach credential material and the tenant role
     * really cannot rewrite Flyway history. Skipped when a role is not provisioned (S3: V26 must still
     * apply on any target); the missing role is then caught at application startup instead — see
     * {@code RlsStartupGuard}, which refuses to boot under {@code restricted-role=true}.
     */
    private List<String> verifyRolePrivileges(Statement st) throws Exception {
        List<String> problems = new ArrayList<>();
        for (String role : new String[]{"jotheca_app", "jotheca_readall"}) {
            if (!roleExists(st, role)) {
                log.warn("Role {} is not provisioned; skipping its privilege verification "
                        + "(run infra/db/provision-roles.sh). The application will refuse to start "
                        + "under app.datasource.restricted-role=true until it exists.", role);
                continue;
            }
            for (String table : CREDENTIAL_TABLES) {
                if (hasPrivilege(st, role, table, "SELECT")) {
                    problems.add(role + " can still SELECT " + table + " (token hashes must be unreachable)");
                }
            }
            if (hasPrivilege(st, role, "users", "SELECT")) {
                problems.add(role + " still has table-level SELECT on users (must be column-level only)");
            }
            if (!hasColumnPrivilege(st, role, "users", "id")) {
                problems.add(role + " cannot SELECT users.id (the platform plane needs at least a count)");
            }
            for (String col : new String[]{"password_hash", "mfa_secret", "mfa_enabled"}) {
                if (hasColumnPrivilege(st, role, "users", col)) {
                    problems.add(role + " can SELECT users." + col + " (credential material must be withheld)");
                }
            }
        }
        if (roleExists(st, "jotheca_app")) {
            for (String priv : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                if (hasPrivilege(st, "jotheca_app", FLYWAY_HISTORY_TABLE, priv)) {
                    problems.add("jotheca_app can still " + priv + " " + FLYWAY_HISTORY_TABLE);
                }
            }
        }
        if (roleExists(st, "jotheca_readall")) {
            for (String priv : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                if (hasPrivilege(st, "jotheca_readall", "memberships", priv)) {
                    problems.add("jotheca_readall can " + priv + " memberships (the platform read role must be read-only)");
                }
            }
        }
        return problems;
    }

    private static boolean roleExists(Statement st, String role) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
            return rs.next();
        }
    }

    private static boolean hasPrivilege(Statement st, String role, String table, String privilege)
            throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT has_table_privilege('" + role + "', '" + table
                + "', '" + privilege + "')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static boolean hasColumnPrivilege(Statement st, String role, String table, String column)
            throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT has_column_privilege('" + role + "', '" + table
                + "', '" + column + "', 'SELECT')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
