package com.oficina.functions.bootstrap;

import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;

/** Instantiate once in the Lambda bootstrap and share across every JDBC adapter. */
public final class ConnectionProvider implements AutoCloseable {
    public record Config(String host, int port, String database, String username, String password, Path rootCertificate) {
        public Config {
            if (host == null || !host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535
                    || database == null || !database.matches("[A-Za-z0-9_]+")
                    || username == null || username.isBlank() || password == null || password.isBlank()
                    || rootCertificate == null || !rootCertificate.isAbsolute()) {
                throw new IllegalArgumentException("Invalid database configuration");
            }
        }
        @Override public String toString() { return "DatabaseConfig[redacted]"; }
    }

    @FunctionalInterface interface Connector { Connection connect(String url, Properties properties) throws SQLException; }
    private final Config config;
    private final Connector connector;
    private Connection cached;

    public ConnectionProvider(Config config) { this(config, DriverManager::getConnection); }
    ConnectionProvider(Config config, Connector connector) {
        this.config = Objects.requireNonNull(config);
        this.connector = Objects.requireNonNull(connector);
    }

    /** The caller owns statements/results; this provider owns the sole cached connection. */
    public synchronized Connection connection() throws SQLException {
        if (cached != null) {
            try {
                if (!cached.isClosed() && cached.isValid(2)) return cached;
            } catch (SQLException ignored) {
                // Frozen Lambda environments can resume with a stale socket.
            }
            discard(cached);
        }
        Connection opened = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password());
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslrootcert", config.rootCertificate().toString());
            properties.setProperty("connectTimeout", "3");
            properties.setProperty("loginTimeout", "5");
            properties.setProperty("socketTimeout", "5");
            properties.setProperty("cancelSignalTimeout", "2");
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("readOnlyMode", "always");
            opened = connector.connect("jdbc:postgresql://" + config.host() + ":" + config.port() + "/" + config.database(), properties);
            opened.setAutoCommit(true);
            opened.setReadOnly(true);
            cached = opened;
            return cached;
        } catch (SQLException ex) {
            closeQuietly(opened);
            // Driver messages may contain JDBC URLs, user names and authentication details.
            throw new SQLException("Database connection unavailable", "08001");
        }
    }

    public synchronized void discard(Connection connection) {
        if (connection != null && connection == cached) {
            closeQuietly(cached);
            cached = null;
        }
    }

    @Override public synchronized void close() { discard(cached); }
    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { /* No dependency payload in logs. */ }
        }
    }
}
