package com.jotheca.tenancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jotheca.identity.security.JwtService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-backed request-path verification for tenant resolution. The fixture is seeded by the
 * disposable database owner, while the Spring application connects as jotheca_app and must resolve
 * membership through TenantAwareDataSource before the request reaches the controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantResolutionPostgresIntegrationTest.OwnerFixtureConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TenantResolutionPostgresIntegrationTest {

    private static final String OWNER_PASSWORD = "owner-password";
    private static final String MIGRATE_PASSWORD = "migrate-password";
    private static final String APP_PASSWORD = "app-password";
    private static final String READALL_PASSWORD = "readall-password";
    private static final String JWT_SECRET =
            "postgres-tenant-resolution-test-secret-012345678901234567890123456789";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withPassword(OWNER_PASSWORD);

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        provisionRuntimeRoles();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "jotheca_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "jotheca_migrate");
        registry.add("spring.flyway.password", () -> MIGRATE_PASSWORD);
        registry.add("app.datasource.restricted-role", () -> "true");
        registry.add("app.datasource.platform-read.username", () -> "jotheca_readall");
        registry.add("app.datasource.platform-read.password", () -> READALL_PASSWORD);
        registry.add("app.jwt-secret", () -> JWT_SECRET);
        registry.add("management.health.mail.enabled", () -> "false");
        registry.add("app.rate-limit.backend", () -> "memory");
        registry.add("app.storage.provider", () -> "memory");
    }

    private static void provisionRuntimeRoles() {
        try (Connection connection = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_migrate') THEN
                        CREATE ROLE jotheca_migrate LOGIN PASSWORD 'migrate-password' NOSUPERUSER NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_app') THEN
                        CREATE ROLE jotheca_app LOGIN PASSWORD 'app-password' NOSUPERUSER NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_readall') THEN
                        CREATE ROLE jotheca_readall LOGIN PASSWORD 'readall-password' NOSUPERUSER BYPASSRLS;
                      END IF;
                    END $$;
                    """);
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            String database;
            try (var result = statement.executeQuery("SELECT quote_ident(current_database())")) {
                result.next();
                database = result.getString(1);
            }
            statement.execute("ALTER DATABASE " + database + " OWNER TO jotheca_migrate");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to provision disposable PostgreSQL test roles", ex);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test
    void approvedMemberPassesThroughRealFilterAndRlsDatasource() throws Exception {
        String token = jwt.createAccessToken("user-a", "member-a@example.test", "USER");

        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Library-Id", "LIB-TESTA"))
                .andExpect(status().isOk());
    }

    @Test
    void nonmemberIsDeniedByTheRealMembershipLookup() throws Exception {
        String token = jwt.createAccessToken("user-b", "member-b@example.test", "USER");

        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Library-Id", "LIB-TESTA"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnerFixtureConfiguration {

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        ApplicationRunner ownerFixture() {
            return new ApplicationRunner() {
                @Override
                public void run(ApplicationArguments args) throws Exception {
                    try (Connection connection = DriverManager.getConnection(
                            pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                         Statement statement = connection.createStatement()) {
                        statement.execute("""
                                INSERT INTO users
                                    (id, email, password_hash, full_name, global_role, status,
                                     email_verified_at, created_at, updated_at)
                                VALUES
                                    ('user-a', 'member-a@example.test', 'test-hash-a', 'Member A',
                                     'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                                    ('user-b', 'member-b@example.test', 'test-hash-b', 'Member B',
                                     'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                """);
                        statement.execute("""
                                INSERT INTO libraries
                                    (id, uid, slug, name, type, status, join_policy, visibility,
                                     member_count, allow_anonymous_reviews, created_at, updated_at)
                                VALUES
                                    ('library-a', 'LIB-TESTA', 'tenant-resolution-test', 'Tenant Resolution Test',
                                     'PUBLIC', 'ACTIVE', 'PUBLIC', TRUE, 1, FALSE,
                                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                """);
                        statement.execute("""
                                INSERT INTO memberships
                                    (id, tenant_id, user_id, role, membership_type, status,
                                     membership_number, joined_at, created_at, updated_at)
                                VALUES
                                    ('membership-a', 'library-a', 'user-a', 'LIBRARY_ADMIN', 'STAFF',
                                     'APPROVED', 'TR-0001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                """);
                    }
                }
            };
        }
    }
}
