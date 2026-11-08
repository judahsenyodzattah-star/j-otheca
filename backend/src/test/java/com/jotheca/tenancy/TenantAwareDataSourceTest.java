package com.jotheca.tenancy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jotheca.common.tenant.TenantAwareDataSource;
import com.jotheca.common.tenant.TenantContext;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TenantAwareDataSourceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void successfulTenantArmingAllowsConnectionUse() throws Exception {
        Connection raw = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        DataSource source = dataSource(raw);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(raw.prepareStatement(anyString())).thenReturn(statement);
        TenantContext.setProvisionalDatabaseTenant("library-a");

        Connection connection = wrapped(source).getConnection();
        connection.setAutoCommit(false);

        verify(statement).setString(1, "library-a");
        verify(statement).execute();
        verify(raw, never()).close();
    }

    @Test
    void failedTenantArmingPropagatesAndBlocksSqlUntilCleanup() throws Exception {
        Connection raw = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        DataSource source = dataSource(raw);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(raw.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new java.sql.SQLException("set_config failed")).when(statement).execute();
        TenantContext.setProvisionalDatabaseTenant("library-a");

        Connection connection = wrapped(source).getConnection();

        assertThatThrownBy(() -> connection.setAutoCommit(false))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("Failed to arm RLS tenant scope");
        assertThatThrownBy(connection::createStatement)
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("connection is unavailable");

        // Spring's transaction cleanup owns release of the acquired connection.
        connection.rollback();
        connection.close();
        verify(raw).rollback();
        verify(raw).close();
    }

    @Test
    void datasourceUnwrapReturnsOnlyTheGuardedProxy() throws Exception {
        DataSource source = dataSource(mock(Connection.class));
        DataSource guarded = wrapped(source);

        org.assertj.core.api.Assertions.assertThat(guarded.isWrapperFor(DataSource.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(guarded.unwrap(DataSource.class)).isSameAs(guarded);
        org.assertj.core.api.Assertions.assertThat(guarded.isWrapperFor(Runnable.class)).isFalse();
        assertThatThrownBy(() -> guarded.unwrap(Runnable.class))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void connectionUnwrapReturnsOnlyTheGuardedProxy() throws Exception {
        Connection raw = mock(Connection.class);
        Connection guarded = wrapped(dataSource(raw)).getConnection();

        org.assertj.core.api.Assertions.assertThat(guarded.isWrapperFor(Connection.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(guarded.unwrap(Connection.class)).isSameAs(guarded);
        org.assertj.core.api.Assertions.assertThat(guarded.isWrapperFor(Runnable.class)).isFalse();
        assertThatThrownBy(() -> guarded.unwrap(Runnable.class))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void failedTenantArmingBlocksEveryUnwrapPath() throws Exception {
        Connection raw = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(raw.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new java.sql.SQLException("set_config failed")).when(statement).execute();
        TenantContext.setProvisionalDatabaseTenant("library-a");

        Connection guarded = wrapped(dataSource(raw)).getConnection();
        assertThatThrownBy(() -> guarded.setAutoCommit(false))
                .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> guarded.unwrap(Connection.class))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void springTransactionManagerRollsBackAndReleasesFailedConnection() throws Exception {
        Connection failed = mock(Connection.class);
        Connection subsequent = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(failed.getAutoCommit()).thenReturn(true);
        when(failed.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(failed.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new java.sql.SQLException("set_config failed")).when(statement).execute();
        when(subsequent.getAutoCommit()).thenReturn(true);
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(failed, subsequent);
        TenantContext.setProvisionalDatabaseTenant("library-a");
        DataSource guarded = wrapped(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(guarded));

        assertThatThrownBy(() -> transactions.execute(status -> null))
                .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        verify(failed, never()).rollback();
        verify(failed).close();

        TenantContext.clear();
        transactions.execute(status -> null);
        verify(subsequent).setAutoCommit(false);
        verify(subsequent).commit();
        verify(subsequent).close();
    }

    private static DataSource dataSource(Connection raw) throws java.sql.SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(raw);
        return source;
    }

    private static DataSource wrapped(DataSource source) throws Exception {
        Method factory = TenantAwareDataSource.class
                .getDeclaredMethod("tenantAwareDataSourcePostProcessor");
        factory.setAccessible(true);
        BeanPostProcessor processor = (BeanPostProcessor) factory.invoke(null);
        return (DataSource) processor.postProcessAfterInitialization(source, "tenantDataSource");
    }
}
