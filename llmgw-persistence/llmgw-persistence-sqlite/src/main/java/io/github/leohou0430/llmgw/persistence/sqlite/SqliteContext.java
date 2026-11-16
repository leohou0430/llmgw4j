package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite-backed {@link DatabaseContext}: one JDBC connection per context, opened in
 * WAL journal mode with a configurable busy timeout and foreign keys enforced.
 * Instances are isolated by database file and own only their own connection, so
 * closing one never affects another.
 */
public final class SqliteContext implements DatabaseContext {
    private final Connection connection;
    private boolean closed;

    public SqliteContext(Path databaseFile, long busyTimeoutMillis) throws SQLException {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException failure) {
            opened.close();
            throw failure;
        }
        this.connection = opened;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to close SQLite connection", failure);
        }
    }
}