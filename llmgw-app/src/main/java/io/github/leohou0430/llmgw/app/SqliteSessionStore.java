package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable session store backed by the {@code session} table (migration V5). Sessions are
 * 256-bit random server-side ids; expiry is enforced on read. A live session survives a
 * process restart (the cookie keeps working). The shared {@link DatabaseContext} connection
 * is never closed here.
 */
public final class SqliteSessionStore implements SessionStore {
    private final DatabaseContext database;
    private final SecureRandom random = new SecureRandom();

    public SqliteSessionStore(DatabaseContext database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Session create(String accountId, long ttlMillis, long nowEpochMs) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long expiresAt = nowEpochMs + ttlMillis;
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "INSERT INTO session (id, account_id, expires_at_epoch_ms, token_version, created_at)"
                            + " VALUES (?,?,?,?,?)");
            try {
                statement.setString(1, id);
                statement.setString(2, accountId);
                statement.setLong(3, expiresAt);
                statement.setLong(4, 0L);
                statement.setLong(5, nowEpochMs);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to create session", failure);
        }
        return new Session(id, accountId, expiresAt, 0L);
    }

    @Override
    public Optional<Session> find(String sessionId, long nowEpochMs) {
        if (sessionId == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT id, account_id, expires_at_epoch_ms, token_version FROM session WHERE id = ?");
            try {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    long expiresAt = rows.getLong("expires_at_epoch_ms");
                    if (nowEpochMs >= expiresAt) {
                        return Optional.empty();
                    }
                    return Optional.of(new Session(rows.getString("id"), rows.getString("account_id"),
                            expiresAt, rows.getLong("token_version")));
                }
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read session", failure);
        }
    }

    @Override
    public void revoke(String sessionId) {
        run("DELETE FROM session WHERE id = ?", sessionId);
    }

    @Override
    public void revokeAllForAccount(String accountId) {
        run("DELETE FROM session WHERE account_id = ?", accountId);
    }

    private void run(String sql, String parameter) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(sql);
            try {
                statement.setString(1, parameter);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to modify session", failure);
        }
    }
}