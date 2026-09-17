package com.oficina.functions.bootstrap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionProviderTest {
    private final ConnectionProvider.Config config = new ConnectionProvider.Config("db.example.invalid", 5432,
            "oficina", "auth_reader", "test-secret", Path.of("target/root.crt").toAbsolutePath());
    private final ConnectionProvider.Connector connector = mock(ConnectionProvider.Connector.class);
    private final Connection first = mock(Connection.class);
    private final Connection second = mock(Connection.class);
    private final ConnectionProvider provider = new ConnectionProvider(config, connector);

    @Test void reusesOneConnectionWithCertificateValidationAndBoundedTimeouts() throws Exception {
        when(connector.connect(anyString(), any())).thenReturn(first);
        when(first.isValid(2)).thenReturn(true);
        assertThat(provider.connection()).isSameAs(first);
        assertThat(provider.connection()).isSameAs(first);
        var properties = ArgumentCaptor.forClass(Properties.class);
        verify(connector).connect(eq("jdbc:postgresql://db.example.invalid:5432/oficina"), properties.capture());
        assertThat(properties.getValue()).containsEntry("sslmode", "verify-full")
                .containsEntry("sslrootcert", config.rootCertificate().toString())
                .containsEntry("connectTimeout", "3").containsEntry("loginTimeout", "5")
                .containsEntry("socketTimeout", "5").containsEntry("cancelSignalTimeout", "2")
                .containsEntry("readOnlyMode", "always");
        verify(first).setAutoCommit(true);
        verify(first).setReadOnly(true);
        provider.close();
        verify(first).close();
        assertThat(config.toString()).isEqualTo("DatabaseConfig[redacted]");
    }

    @Test void staleConnectionClosesBeforeOpeningReplacement() throws Exception {
        when(connector.connect(anyString(), any())).thenReturn(first, second);
        when(first.isValid(2)).thenReturn(false);
        assertThat(provider.connection()).isSameAs(first);
        assertThat(provider.connection()).isSameAs(second);
        var order = inOrder(connector, first);
        order.verify(connector).connect(anyString(), any());
        order.verify(first).close();
        order.verify(connector).connect(anyString(), any());
        provider.close();
        verify(second).close();
    }

    @Test void closedAndValidationErrorConnectionsAreRecovered() throws Exception {
        for (boolean closed : new boolean[]{false, true}) {
            reset(first, second, connector);
            when(connector.connect(anyString(), any())).thenReturn(first, second);
            when(first.isClosed()).thenReturn(closed);
            if (!closed) when(first.isValid(2)).thenThrow(new SQLException("stale"));
            provider.connection();
            assertThat(provider.connection()).isSameAs(second);
            verify(first).close();
            provider.close();
        }
    }

    @Test void failedInitializationClosesSocketAndDoesNotLeakCredentials() throws Exception {
        when(connector.connect(anyString(), any())).thenReturn(first);
        doThrow(new SQLException("test-secret jdbc://private")).when(first).setReadOnly(true);
        assertThatThrownBy(provider::connection).isInstanceOf(SQLException.class)
                .hasMessage("Database connection unavailable").hasNoCause();
        verify(first).close();
    }

    @Test void failedConnectionAndFailedCloseAreRedacted() throws Exception {
        when(connector.connect(anyString(), any())).thenThrow(new SQLException("test-secret jdbc://private"));
        assertThatThrownBy(provider::connection).hasMessage("Database connection unavailable").hasNoCause();
        reset(connector);
        when(connector.connect(anyString(), any())).thenReturn(first);
        doThrow(new SQLException("secret")).when(first).close();
        provider.connection();
        assertThatCode(provider::close).doesNotThrowAnyException();
        provider.discard(second);
        verify(second, never()).close();
    }

    @Test void connectionConfigCannotInjectUrlOptionsOrDisableTls() {
        assertThatThrownBy(() -> new ConnectionProvider.Config("db?sslmode=disable", 5432,
                "db", "user", "pwd", config.rootCertificate())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionProvider.Config("db", 0,
                "db", "user", "pwd", config.rootCertificate())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionProvider.Config("db", 5432,
                "db?sslmode=disable", "user", "pwd", config.rootCertificate())).isInstanceOf(IllegalArgumentException.class);
    }
}
