package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable account store backed by SQLite (the {@code account} table from migration V4). The
 * first administrator created via env bootstrap survives restart; the shared
 * {@link DatabaseContext} connection is owned by the context and is never closed here.
 */
public final class SqliteAccountStore implements AccountStore {
    private final DatabaseContext database;

    public SqliteAccountStore(DatabaseContext database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Account create(Account account) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "INSERT INTO account (id, username, password_hash, role, tenant_id, project_id, enabled, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)");
            try {
                statement.setString(1, account.id());
                statement.setString(2, account.username());
                statement.setString(3, account.passwordHash());
                statement.setString(4, account.role().name());
                statement.setString(5, account.scope().tenantId());
                statement.setString(6, account.scope().projectId());
                statement.setInt(7, account.enabled() ? 1 : 0);
                statement.setLong(8, System.currentTimeMillis());
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("duplicate or invalid account: " + account.username(), failure);
        }
        return account;
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return queryOne("SELECT * FROM account WHERE username = ?", username);
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return queryOne("SELECT * FROM account WHERE id = ?", accountId);
    }

    @Override
    public void updatePassword(String username, String newPasswordHash) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "UPDATE account SET password_hash = ? WHERE username = ?");
            try {
                statement.setString(1, newPasswordHash);
                statement.setString(2, username);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to update password", failure);
        }
    }

    @Override
    public boolean existsAny() {
        try {
            PreparedStatement statement = database.connection().prepareStatement("SELECT 1 FROM account LIMIT 1");
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to query accounts", failure);
        }
    }

    private Optional<Account> queryOne(String sql, String parameter) {
        if (parameter == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement statement = database.connection().prepareStatement(sql);
            try {
                statement.setString(1, parameter);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(read(rows)) : Optional.empty();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read account", failure);
        }
    }

    private static Account read(ResultSet rows) throws SQLException {
        return new Account(
                rows.getString("id"),
                rows.getString("username"),
                rows.getString("password_hash"),
                Role.valueOf(rows.getString("role")),
                new Scope(rows.getString("tenant_id"), rows.getString("project_id")),
                rows.getInt("enabled") != 0);
    }
}