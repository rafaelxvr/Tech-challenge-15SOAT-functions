package com.oficina.functions.adapter.jdbc;

import com.oficina.functions.bootstrap.ConnectionProvider;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class JdbcClienteLookupTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("auth_test").withUsername("test_admin").withPassword("fixture_admin_only")
            .withCommand("sh", "-c", "openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost "
                    + "-addext subjectAltName=DNS:localhost,DNS:host.docker.internal "
                    + "-keyout /tmp/auth-server.key -out /tmp/auth-server.crt 2>/dev/null "
                    + "&& chown postgres:postgres /tmp/auth-server.key && chmod 600 /tmp/auth-server.key "
                    + "&& exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/auth-server.crt -c ssl_key_file=/tmp/auth-server.key");
    private static final UUID CPF_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CNPJ_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String CPF = "39053344705";
    private static Connection admin;
    private static Path certificate;
    private Connection reader;
    private ConnectionProvider provider;
    private JdbcClienteLookup lookup;

    @BeforeAll static void createAppOwnedViewAndRestrictedRole() throws Exception {
        certificate = Files.createTempFile("oficina-f2-ca-", ".crt");
        Files.write(certificate, POSTGRES.copyFileFromContainer("/tmp/auth-server.crt", java.io.InputStream::readAllBytes));
        admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE app_owner NOLOGIN");
            statement.execute("CREATE ROLE auth_reader LOGIN PASSWORD 'fixture_reader_only'");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE TABLE clientes (id uuid PRIMARY KEY, cpf varchar(11), cnpj varchar(14), ativo boolean NOT NULL, email varchar(255), versao_identidade bigint NOT NULL)");
            statement.execute("ALTER TABLE clientes OWNER TO app_owner");
            statement.execute("CREATE VIEW auth_cliente_snapshot AS SELECT id,cpf,ativo,email,versao_identidade FROM clientes WHERE cpf IS NOT NULL AND cnpj IS NULL");
            statement.execute("ALTER VIEW auth_cliente_snapshot OWNER TO app_owner");
            statement.execute("GRANT USAGE ON SCHEMA public TO auth_reader");
            statement.execute("GRANT SELECT ON auth_cliente_snapshot TO auth_reader");
            statement.execute("INSERT INTO clientes VALUES ('" + CPF_ID + "','" + CPF + "',NULL,true,'before@example.invalid',1),('" + CNPJ_ID + "',NULL,'11222333000181',true,'cnpj@example.invalid',1)");
        }
    }

    @BeforeEach void connectRestrictedUser() throws Exception {
        reader = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "auth_reader", "fixture_reader_only");
        // Role/view integration is real PostgreSQL. TLS/provider lifecycle is tested separately.
        provider = mock(ConnectionProvider.class);
        when(provider.connection()).thenReturn(reader);
        lookup = new JdbcClienteLookup(provider);
        try (Statement statement = admin.createStatement()) {
            statement.execute("UPDATE clientes SET email='before@example.invalid', versao_identidade=1, ativo=true WHERE cpf='" + CPF + "'");
        }
    }

    @AfterEach void closeReader() throws Exception { reader.close(); }
    @AfterAll static void closeAdmin() throws Exception {
        if (admin != null) admin.close();
        if (certificate != null) Files.deleteIfExists(certificate);
    }

    @Test void cpfAndIdReadOnlyTheAuthViewWhileCnpjIsAbsent() throws Exception {
        var customer = lookup.porCpf(CPF).orElseThrow();
        assertThat(customer.id()).isEqualTo(CPF_ID);
        assertThat(customer.cpf()).isEqualTo(CPF);
        assertThat(customer.ativo()).isTrue();
        assertThat(lookup.porId(CPF_ID)).contains(customer);
        assertThat(lookup.porCpf("11222333000181")).isEmpty();
        assertThat(lookup.porId(CNPJ_ID)).isEmpty();
        assertThat(reader.isClosed()).isFalse();
    }

    @Test void restrictedRoleCannotSelectOrUpdateBaseTableOrUpdateView() {
        for (String sql : new String[]{"SELECT * FROM clientes", "UPDATE clientes SET ativo=false", "UPDATE auth_cliente_snapshot SET ativo=false"}) {
            assertThatThrownBy(() -> {
                try (Statement statement = reader.createStatement()) { statement.execute(sql); }
            }).isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
    }

    @Test void injectionShapedCpfIsDataAndCannotReturnAnyCustomer() {
        assertThat(lookup.porCpf("' OR '1'='1")).isEmpty();
        assertThat(lookup.porCpf("39053344705'; UPDATE clientes SET ativo=false; --")).isEmpty();
        assertThat(lookup.porCpf(CPF)).isPresent();
    }

    @Test void contactAndIdentityChangesAreVisibleOnTheNextLookup() throws Exception {
        assertThat(lookup.porId(CPF_ID).orElseThrow().email()).isEqualTo("before@example.invalid");
        try (Statement statement = admin.createStatement()) {
            statement.execute("UPDATE clientes SET email='after@example.invalid', versao_identidade=2, ativo=false WHERE cpf='" + CPF + "'");
        }
        var changed = lookup.porId(CPF_ID).orElseThrow();
        assertThat(changed.email()).isEqualTo("after@example.invalid");
        assertThat(changed.versaoIdentidade()).isEqualTo(2);
        assertThat(changed.ativo()).isFalse();
    }

    @Test void queryUsesBoundParameterTimeoutAndClosesResourcesButKeepsConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(provider.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        String injection = "' OR 1=1 --";
        assertThat(lookup.porCpf(injection)).isEmpty();
        verify(connection).prepareStatement("SELECT id,cpf,ativo,email,versao_identidade FROM auth_cliente_snapshot WHERE cpf=?");
        verify(statement).setString(1, injection);
        verify(statement).setQueryTimeout(3);
        verify(statement).close();
        verify(rows).close();
        verify(connection, never()).close();
        lookup.porId(CPF_ID);
        verify(connection).prepareStatement("SELECT id,cpf,ativo,email,versao_identidade FROM auth_cliente_snapshot WHERE id=?");
        verify(statement).setObject(1, CPF_ID);
    }

    @Test void databaseErrorsAreRedactedAndBrokenConnectionIsDiscarded() throws Exception {
        Connection connection = mock(Connection.class);
        when(provider.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("secret password jdbc://host cpf", "08006"));
        assertThatThrownBy(() -> lookup.porCpf(CPF)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Customer lookup unavailable").hasNoCause();
        verify(provider).discard(connection);
    }

    @Test void productionProviderValidatesTlsReusesConnectionAndRecoversTerminatedBackend() throws Exception {
        try (ConnectionProvider real = new ConnectionProvider(config(POSTGRES.getHost(), certificate))) {
            Connection original = real.connection();
            int pid;
            try (Statement statement = original.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT pid,ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("ssl")).isTrue();
                pid = rows.getInt("pid");
            }
            assertThat(real.connection()).isSameAs(original);
            assertThat(new JdbcClienteLookup(real).porCpf(CPF)).isPresent();
            try (PreparedStatement terminate = admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
                terminate.setInt(1, pid);
                terminate.execute();
            }
            Connection recovered = real.connection();
            assertThat(recovered).isNotSameAs(original);
            assertThat(original.isClosed()).isTrue();
            assertThat(new JdbcClienteLookup(real).porId(CPF_ID)).isPresent();
        }
    }

    @Test void productionProviderRejectsUntrustedCertificateAndWrongHostname() throws Exception {
        Path untrusted = Files.createTempFile("oficina-f2-untrusted-", ".crt");
        try (ConnectionProvider badTrust = new ConnectionProvider(config(POSTGRES.getHost(), untrusted));
             ConnectionProvider badHost = new ConnectionProvider(config("127.0.0.1", certificate))) {
            assertThatThrownBy(badTrust::connection).isInstanceOf(SQLException.class)
                    .hasMessage("Database connection unavailable").hasNoCause();
            assertThatThrownBy(badHost::connection).isInstanceOf(SQLException.class)
                    .hasMessage("Database connection unavailable").hasNoCause();
        } finally { Files.deleteIfExists(untrusted); }
    }

    private static ConnectionProvider.Config config(String host, Path rootCertificate) {
        return new ConnectionProvider.Config(host, POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName(),
                "auth_reader", "fixture_reader_only", rootCertificate);
    }
}
