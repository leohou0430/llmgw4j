package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyStore;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SQLite {@link KeyStore}: the same observable contract as the JSON store (at-rest
 * hashing, rotation linkage, legacy window, full {@link ApiKeyRegistry.ApiKey}
 * round-trip) backed by the {@code key_store} / {@code key_allowed_model} /
 * {@code key_metadata} tables. Each mutation is one transaction; the raw key is
 * never stored in hashed mode, only its SHA-256 digest.
 */
public final class SqliteKeyStore implements KeyStore {
    private final DatabaseContext database;
    private final boolean hashAtRest;

    public SqliteKeyStore(DatabaseContext database, boolean hashAtRest) {
        this.database = Objects.requireNonNull(database, "database");
        this.hashAtRest = hashAtRest;
    }

    private String storeKey(String token) {
        if (!hashAtRest) {
            return token;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void upsert(String token, ApiKeyRegistry.ApiKey key) {
        mutate(connection -> write(connection, storeKey(token), key));
    }

    @Override
    public void delete(String token) {
        deleteAtRest(storeKey(token));
    }

    @Override
    public void deleteAtRest(String atRestKey) {
        mutate(connection -> remove(connection, atRestKey));
    }

    @Override
    public void rotate(String oldToken, String newToken, long overlapEnd) {
        rotateAtRest(storeKey(oldToken), storeKey(newToken), overlapEnd);
    }

    @Override
    public void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd) {
        mutate(connection -> markRotated(connection, oldAtRest, newAtRest, overlapEnd));
    }

    public java.util.Optional<StoredKey> findToken(String token) {
        return findAtRest(storeKey(token));
    }

    public java.util.Optional<StoredKey> findAtRest(String atRest) {
        try {
            return database.read(connection -> {
                try (var statement = connection.prepareStatement("SELECT * FROM key_store WHERE at_rest=?")) {
                    statement.setString(1, atRest);
                    try (var rs = statement.executeQuery()) {
                        if (!rs.next()) return java.util.Optional.empty();
                        var key = new ApiKeyRegistry.ApiKey(rs.getString("id"), rs.getString("name"),
                                rs.getLong("quota_tokens"), models(connection, atRest), rs.getLong("expires_at_epoch_ms"),
                                rs.getString("group_name"), rs.getLong("budget_micro_usd"), metadata(connection, atRest));
                        return java.util.Optional.of(new StoredKey(key, rs.getString("rotated_to"), rs.getLong("legacy_until")));
                    }
                }
            });
        } catch (SQLException e) { throw new IllegalStateException("failed to read credential", e); }
    }

    public void importAtRest(String atRest, StoredKey stored) {
        mutate(connection -> {
            write(connection, atRest, stored.key());
            if (stored.rotatedTo() != null) markRotated(connection, atRest, stored.rotatedTo(), stored.legacyUntil());
        });
    }

    @Override
    public Map<String, StoredKey> load() {
        try { return database.read(this::load); }
        catch (SQLException failure) { throw new IllegalStateException("failed to load API key state", failure); }
    }

    private Map<String, StoredKey> load(Connection connection) {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT at_rest, id, name, quota_tokens, expires_at_epoch_ms, group_name,"
                             + " budget_micro_usd, rotated_to, legacy_until FROM key_store")) {
            while (result.next()) {
                rows.add(new Object[]{
                        result.getString("at_rest"),
                        new ApiKeyRegistry.ApiKey(
                                result.getString("id"), result.getString("name"),
                                result.getLong("quota_tokens"),
                                Set.of(), // placeholder, replaced below
                                result.getLong("expires_at_epoch_ms"),
                                result.getString("group_name"),
                                result.getLong("budget_micro_usd"),
                                Map.of()),
                        result.getString("rotated_to"),
                        result.getLong("legacy_until")});
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to load API key state", failure);
        }

        Map<String, StoredKey> out = new LinkedHashMap<>();
        try {
            for (Object[] row : rows) {
                String atRest = (String) row[0];
                ApiKeyRegistry.ApiKey stored = (ApiKeyRegistry.ApiKey) row[1];
                ApiKeyRegistry.ApiKey key = new ApiKeyRegistry.ApiKey(
                        stored.id(), stored.name(), stored.quotaTokens(), models(connection, atRest),
                        stored.expiresAtEpochMs(), stored.group(), stored.budgetMicroUsd(),
                        metadata(connection, atRest));
                String rotatedTo = (String) row[2];
                long legacyUntil = (Long) row[3];
                out.put(atRest, new StoredKey(key,
                        rotatedTo == null || rotatedTo.isEmpty() ? null : rotatedTo,
                        legacyUntil > 0 ? legacyUntil : 0));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to load API key children", failure);
        }
        return out;
    }

    private static Set<String> models(Connection connection, String atRest) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT model FROM key_allowed_model WHERE at_rest=? ORDER BY model")) {
            statement.setString(1, atRest);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(rows.getString(1));
                }
            }
        }
        return out;
    }

    private static Map<String, String> metadata(Connection connection, String atRest) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT k, v FROM key_metadata WHERE at_rest=? ORDER BY k")) {
            statement.setString(1, atRest);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return out;
    }

    private static void write(Connection connection, String atRest, ApiKeyRegistry.ApiKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO key_store (at_rest, id, name, quota_tokens, expires_at_epoch_ms, group_name,"
                        + " budget_micro_usd, rotated_to, legacy_until) VALUES (?,?,?,?,?,?,?,NULL,0)"
                        + " ON CONFLICT(at_rest) DO UPDATE SET id=excluded.id, name=excluded.name,"
                        + " quota_tokens=excluded.quota_tokens, expires_at_epoch_ms=excluded.expires_at_epoch_ms,"
                        + " group_name=excluded.group_name, budget_micro_usd=excluded.budget_micro_usd,"
                        + " rotated_to=NULL, legacy_until=0")) {
            statement.setString(1, atRest);
            statement.setString(2, key.id());
            statement.setString(3, key.name());
            statement.setLong(4, key.quotaTokens());
            statement.setLong(5, key.expiresAtEpochMs());
            statement.setString(6, key.group());
            statement.setLong(7, key.budgetMicroUsd());
            statement.executeUpdate();
        }
        deleteChildren(connection, atRest);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO key_allowed_model (at_rest, model) VALUES (?,?)")) {
            for (String model : key.allowedModels()) {
                statement.setString(1, atRest);
                statement.setString(2, model);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO key_metadata (at_rest, k, v) VALUES (?,?,?)")) {
            for (Map.Entry<String, String> entry : key.metadata().entrySet()) {
                statement.setString(1, atRest);
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void remove(Connection connection, String atRest) throws SQLException {
        deleteChildren(connection, atRest);
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM key_store WHERE at_rest=?")) {
            statement.setString(1, atRest);
            statement.executeUpdate();
        }
    }

    private static void deleteChildren(Connection connection, String atRest) throws SQLException {
        for (String sql : List.of(
                "DELETE FROM key_allowed_model WHERE at_rest=?",
                "DELETE FROM key_metadata WHERE at_rest=?")) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, atRest);
                statement.executeUpdate();
            }
        }
    }

    private static void markRotated(Connection connection, String oldAtRest, String newAtRest, long overlapEnd)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE key_store SET rotated_to=?, legacy_until=? WHERE at_rest=?")) {
            statement.setString(1, newAtRest);
            statement.setLong(2, overlapEnd);
            statement.setString(3, oldAtRest);
            statement.executeUpdate();
        }
    }

    private void mutate(SqlBody body) {
        try {
            database.transaction(connection -> { body.run(connection); return null; });
        } catch (SQLException failure) {
            if (SqliteErrors.isStorageFull(failure)) throw new StorageLimitException("database or disk is full");
            throw new IllegalStateException("failed to persist API key state", failure);
        }
    }

    @FunctionalInterface
    private interface SqlBody {
        void run(Connection connection) throws SQLException;
    }
}
