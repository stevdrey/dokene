package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TenantAwareDataSourceTest {

    @Test
    void abortsThePhysicalConnectionWhenContextPropagationClosesPartially() throws SQLException {
        TenantId tenantId = TenantId.random();
        ScopedValueTenantContextProvider tenantContexts = new ScopedValueTenantContextProvider();
        DatabaseContextSigner signer = mock(DatabaseContextSigner.class);
        when(signer.issueTenantContext(tenantId)).thenReturn(new SignedDatabaseContext(
                "tenant|default|%s|4102444800|0123456789abcdef0123456789abcdef".formatted(tenantId.value()),
                "0".repeat(64),
                Instant.parse("2100-01-01T00:00:00Z")
        ));

        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement propagationStatement = mock(PreparedStatement.class);
        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.getAutoCommit()).thenReturn(true);
        when(targetConnection.prepareStatement(anyString())).thenReturn(propagationStatement);
        when(propagationStatement.execute()).thenReturn(true);
        doThrow(new SQLException("statement close failure")).when(propagationStatement).close();

        TenantAwareDataSource dataSource = new TenantAwareDataSource(targetDataSource, tenantContexts, signer);
        Connection connection = dataSource.getConnection();
        assertThatThrownBy(() -> tenantContexts.callWithTenantId(tenantId, connection::createStatement))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("statement close failure");

        verify(targetConnection).abort(any());
        verify(targetConnection, never()).createStatement();
    }
}
