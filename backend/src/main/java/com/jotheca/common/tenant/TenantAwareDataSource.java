package com.jotheca.common.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Isolation layer 6 of 9 companion: when a transaction begins ({@code setAutoCommit(false)})
 * while a tenant is resolved (or while membership is being checked in a provisional scope), issue
 * {@code SET LOCAL app.tenant_id = '<tenant>'} on that exact
 * connection so Postgres RLS policies (V2__RlsPolicies) apply inside the transaction only.
 * No-ops on H2 (dev/tests) and outside tenant scope (platform plane, Flyway, seeding).
 */
@Configuration
public class TenantAwareDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DataSource ds) || beanName.contains("tenantAwareProxy")) {
                    return bean;
                }
                InvocationHandler handler = (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && method.getParameterCount() <= 2) {
                        Connection raw = (Connection) method.invoke(ds, args);
                        return wrap(raw);
                    }
                    if ("unwrap".equals(method.getName())) {
                        return unwrapGuardedProxy(proxy, args);
                    }
                    if ("isWrapperFor".equals(method.getName())) {
                        return isGuardedProxyFor(proxy, args);
                    }
                    return method.invoke(ds, args);
                };
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                        new Class<?>[]{DataSource.class}, handler);
            }
        };
    }

    private static Connection wrap(Connection raw) {
        final boolean[] tenantArmingFailed = {false};
        InvocationHandler handler = (proxy, method, args) -> {
            if (tenantArmingFailed[0] && !isCleanupMethod(method.getName())) {
                throw new SQLException("Tenant scope was not armed; connection is unavailable");
            }
            if ("unwrap".equals(method.getName())) {
                return unwrapGuardedProxy(proxy, args);
            }
            if ("isWrapperFor".equals(method.getName())) {
                return isGuardedProxyFor(proxy, args);
            }
            Object result = method.invoke(raw, args);
            if ("setAutoCommit".equals(method.getName())
                    && args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                String tenantId = TenantContext.databaseTenantId();
                if (tenantId != null) {
                    TenantContext.recordTenantArming(false);
                    try {
                        String product = raw.getMetaData().getDatabaseProductName().toLowerCase();
                        if (product.contains("postgres")) {
                            try (PreparedStatement ps =
                                         raw.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                                ps.setString(1, tenantId);
                                ps.execute();
                            }
                        }
                        TenantContext.recordTenantArming(true);
                    } catch (Exception e) {
                        tenantArmingFailed[0] = true;
                        log.warn("Failed to arm RLS session variable; transaction initialization aborted");
                        throw new SQLException("Failed to arm RLS tenant scope", e);
                    }
                }
            }
            return result;
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static boolean isCleanupMethod(String methodName) {
        return switch (methodName) {
            case "close", "rollback", "setAutoCommit", "isClosed", "isValid", "abort" -> true;
            default -> false;
        };
    }

    private static Object unwrapGuardedProxy(Object proxy, Object[] args) throws SQLException {
        if (args != null && args.length == 1 && args[0] instanceof Class<?> requested
                && requested.isInterface() && requested.isInstance(proxy)) {
            return proxy;
        }
        throw new SQLException("Requested interface is not implemented by the guarded proxy");
    }

    private static boolean isGuardedProxyFor(Object proxy, Object[] args) {
        return args != null && args.length == 1 && args[0] instanceof Class<?> requested
                && requested.isInterface() && requested.isInstance(proxy);
    }
}
