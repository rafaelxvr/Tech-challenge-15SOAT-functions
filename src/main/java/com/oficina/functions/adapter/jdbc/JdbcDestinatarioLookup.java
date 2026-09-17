package com.oficina.functions.adapter.jdbc;

import com.oficina.functions.bootstrap.ConnectionProvider;
import com.oficina.functions.notification.Destinatario;
import com.oficina.functions.notification.DestinatarioLookup;
import java.sql.*; import java.util.*;

/** Reads only the APP-owned notification snapshot; database privileges enforce no business writes. */
public final class JdbcDestinatarioLookup implements DestinatarioLookup {
    private static final String SQL = "SELECT ordem_id,numero,cliente_id,ativo,email,versao_identidade FROM notificacao_destinatario_snapshot WHERE ordem_id=?";
    private final ConnectionProvider provider;
    public JdbcDestinatarioLookup(ConnectionProvider provider) { this.provider = Objects.requireNonNull(provider); }
    @Override public Optional<Destinatario> porOrdem(UUID ordemId) {
        synchronized (provider) { Connection connection = null; try {
            connection = provider.connection(); try (PreparedStatement statement = connection.prepareStatement(SQL)) { statement.setQueryTimeout(3); statement.setObject(1, ordemId);
                try (ResultSet r = statement.executeQuery()) { if (!r.next()) return Optional.empty(); Destinatario d = new Destinatario(r.getObject("ordem_id", UUID.class), r.getLong("numero"), r.getObject("cliente_id", UUID.class), r.getBoolean("ativo"), r.getString("email"), r.getLong("versao_identidade")); if (r.next()) throw new SQLException("Ambiguous recipient snapshot"); return Optional.of(d); }
            }
        } catch (SQLException e) { provider.discard(connection); throw new IllegalStateException("Recipient lookup unavailable"); } }
    }
}
