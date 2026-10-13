package com.jotheca.common.datasource;

import com.jotheca.audit.AuditService;
import com.jotheca.common.tenant.PlaneContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Phase A platform-plane routing — ACTIVE ONLY when {@code app.datasource.restricted-role=true}
 * (opt-in cutover; default false keeps today's single owner-role datasource, so this config is inert).
 *
 * <p>Least privilege: only TWO pools.
 * <ul>
 *   <li>tenant       -> {@code jotheca_app}     (NOBYPASSRLS, RLS-confined) — all non-platform traffic
 *       AND platform-plane WRITES (confined to the single armed target library via {@code PlatformWriteContext})</li>
 *   <li>platformRead -> {@code jotheca_readall} (BYPASSRLS, SELECT-only)    — GET {@code /api/v1/platform/**}</li>
 * </ul>
 * There is deliberately NO cross-tenant WRITE role. Credentials come from env / secret manager (never
 * committed). Authorization of {@code /api/v1/platform/**} (SUPER_ADMIN) stays in SecurityConfig.
 */
@Configuration
@ConditionalOnProperty(name = "app.datasource.restricted-role", havingValue = "true")
public class PlatformPlaneConfig {

    private final String url;
    private final String tenantUser;
    private final String tenantPass;
    private final String readUser;
    private final String readPass;

    public PlatformPlaneConfig(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String tenantUser,
            @Value("${spring.datasource.password}") String tenantPass,
            @Value("${app.datasource.platform-read.username:jotheca_readall}") String readUser,
            @Value("${app.datasource.platform-read.password:}") String readPass) {
        this.url = url;
        this.tenantUser = tenantUser;
        this.tenantPass = tenantPass;
        this.readUser = readUser;
        this.readPass = readPass;
    }

    private HikariDataSource pool(String name, String user, String pass) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(name);
        cfg.setJdbcUrl(url);
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setUsername(user);
        cfg.setPassword(pass);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "tenantDataSource")
    DataSource tenantDataSource() {
        return pool("jotheca-tenant", tenantUser, tenantPass);
    }

    @Bean(name = "platformReadDataSource")
    DataSource platformReadDataSource() {
        return pool("jotheca-platform-read", readUser, readPass);
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("tenantDataSource") DataSource tenant,
                          @Qualifier("platformReadDataSource") DataSource read) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        targets.put(PlaneContext.TENANT, tenant);
        targets.put(PlaneContext.PLATFORM_READ, read);
        PlaneRoutingDataSource routing = new PlaneRoutingDataSource();
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(tenant); // fail-safe: unset plane -> tenant, never read-all
        return routing;
    }

    @Bean
    WebMvcConfigurer planeRoutingWebMvcConfig(AuditService audit) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PlaneRoutingInterceptor(audit)).addPathPatterns("/api/**");
            }
        };
    }
}
