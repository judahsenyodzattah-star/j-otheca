package com.jotheca.common.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Isolation layer 6 of 9 companion: when a transaction begins ({@code setAutoCommit(false)})
 * while a tenant is resolved, issue {@code SET LOCAL app.tenant_id = '<tenant>'} on that exact
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
                    return method.invoke(ds, args);
                };
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                        new Class<?>[]{DataSource.class}, handler);
            }
        };
    }

    private static Connection wrap(Connection raw) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object result = method.invoke(raw, args);
            if ("setAutoCommit".equals(method.getName())
                    && args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                TenantContext.Current ctx = TenantContext.get();
                if (ctx != null) {
                    try {
                        String product = raw.getMetaData().getDatabaseProductName().toLowerCase();
                        if (product.contains("postgres")) {
                            try (Statement st = raw.createStatement()) {
                                st.execute("SET LOCAL app.tenant_id = '" + ctx.libraryId() + "'");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to arm RLS session variable: {}", e.getMessage());
                    }
                }
            }
            return result;
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }
}
