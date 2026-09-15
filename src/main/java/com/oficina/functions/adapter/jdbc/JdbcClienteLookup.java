package com.oficina.functions.adapter.jdbc;

import com.oficina.functions.auth.ClienteLookup;
import com.oficina.functions.auth.ClienteSnapshot;
import com.oficina.functions.bootstrap.ConnectionProvider;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads the APP-owned view only. Database grants enforce the same boundary. */
public final class JdbcClienteLookup implements ClienteLookup {
    private static final String SELECT = "SELECT id,cpf,ativo,email,versao_identidade FROM auth_cliente_snapshot WHERE ";
    private final ConnectionProvider provider;

    public JdbcClienteLookup(ConnectionProvider provider) { this.provider = Objects.requireNonNull(provider); }

    @Override public Optional<ClienteSnapshot> porCpf(String cpf) { return lookup(SELECT + "cpf=?", cpf); }
    @Override public Optional<ClienteSnapshot> porId(UUID id) { return lookup(SELECT + "id=?", id); }

    private Optional<ClienteSnapshot> lookup(String sql, Object parameter) {
        // JDBC connections are not thread-safe; all adapters sharing the provider share this lock.
        synchronized (provider) {
            Connection connection = null;
            try {
                connection = provider.connection();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(3);
                    if (parameter instanceof String cpf) statement.setString(1, cpf);
                    else statement.setObject(1, parameter);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.empty();
                        ClienteSnapshot customer = new ClienteSnapshot(rows.getObject("id", UUID.class),
                                rows.getString("cpf"), rows.getBoolean("ativo"), rows.getString("email"),
                                rows.getLong("versao_identidade"));
                        if (rows.next()) throw new SQLException("Ambiguous customer snapshot");
                        return Optional.of(customer);
                    }
                }
            } catch (SQLException ex) {
                provider.discard(connection);
                throw new IllegalStateException("Customer lookup unavailable");
            }
        }
    }
}
