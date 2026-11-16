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
    private final java.util.concurrent.locks.ReentrantLock access = new java.util.concurrent.locks.ReentrantLock();
    private Runnable writeGuard = () -> {};
    private int transactionDepth;
    private boolean rollbackOnly;
    private final java.util.List<Runnable> committedActions = new java.util.ArrayList<>();

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
    public <T> T read(SqlWork<T> work) throws SQLException {
        access.lock();
        try {
            if (closed) throw new SQLException("database context is closed");
            return work.run(connection);
        } finally {
            access.unlock();
        }
    }

    @Override
    public <T> T transaction(SqlWork<T> work) throws SQLException {
        access.lock();
        try {
            if (closed) throw new SQLException("database context is closed");
            if (transactionDepth > 0) {
                try { return work.run(connection); }
                catch (SQLException | RuntimeException | Error failure) {
                    rollbackOnly = true;
                    throw failure;
                }
            }
            writeGuard.run();
            connection.setAutoCommit(false);
            transactionDepth = 1;
            rollbackOnly = false;
            try {
                T result = work.run(connection);
                if (rollbackOnly) throw new SQLException("nested transaction failed; transaction rolled back");
                writeGuard.run();
                connection.commit();
                transactionDepth = 0;
                connection.setAutoCommit(true);
                for (Runnable action : java.util.List.copyOf(committedActions)) action.run();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                transactionDepth = 0;
                rollbackOnly = false;
                committedActions.clear();
                connection.setAutoCommit(true);
            }
        } finally {
            access.unlock();
        }
    }

    public void setWriteGuard(Runnable guard) {
        access.lock();
        try { this.writeGuard = java.util.Objects.requireNonNull(guard); }
        finally { access.unlock(); }
    }

    @Override
    public void afterCommit(Runnable action) {
        access.lock();
        try {
            if (transactionDepth > 0) committedActions.add(action); else action.run();
        } finally { access.unlock(); }
    }

    @Override
    public void close() {
        access.lock();
        try {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to close SQLite connection", failure);
        }
        } finally { access.unlock(); }
    }
}
