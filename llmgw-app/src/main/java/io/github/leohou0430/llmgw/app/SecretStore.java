package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.RootKeyMismatchException;
import io.github.leohou0430.llmgw.control.RootKeyMissingException;
import io.github.leohou0430.llmgw.control.SecretCipher;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteErrors;
import io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable store for provider secrets. Only the AEAD ciphertext is persisted; reads decrypt
 * in-process and hand back a masked suffix, so a plaintext credential never leaves the
 * store (and never touches the database). A missing root key fails closed through the
 * cipher, so nothing can be stored as plaintext by accident.
 */
public final class SecretStore {
    private static final String MISMATCH_MESSAGE =
            "configured root key cannot decrypt stored provider secrets";

    private final DatabaseContext database;
    private final SecretCipher cipher;
    private final Runnable writeGuard;

    public SecretStore(DatabaseContext database, SecretCipher cipher) {
        this(database, cipher, () -> { });
    }

    /** {@code writeGuard} runs before each mutation (e.g. disk-cap/free-space check). */
    public SecretStore(DatabaseContext database, SecretCipher cipher, Runnable writeGuard) {
        this.database = Objects.requireNonNull(database, "database");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.writeGuard = Objects.requireNonNull(writeGuard, "writeGuard");
    }

    public record SecretSummary(String id, String tenantId, String projectId, String name, String masked) {
    }

    public String create(String tenantId, String projectId, String name, String plaintext) {
        writeGuard.run();
        requireUsableRootKey();
        String id = UUID.randomUUID().toString();
        String cipherText = cipher.encrypt(plaintext);
        long now = System.currentTimeMillis();
        mutate(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO upstream_secret (id, tenant_id, project_id, name, cipher_text,"
                            + " rotated_from, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, tenantId);
                insert.setString(3, projectId);
                insert.setString(4, name);
                insert.setString(5, cipherText);
                insert.setLong(6, now);
                insert.setLong(7, now);
                insert.executeUpdate();
            }
        });
        return id;
    }

    public void rotate(String tenantId, String projectId, String id, String plaintext) {
        writeGuard.run();
        requireUsableRootKey();
        String cipherText = cipher.encrypt(plaintext);
        long now = System.currentTimeMillis();
        mutate(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE upstream_secret SET cipher_text = ?, updated_at = ?"
                            + " WHERE id = ? AND tenant_id = ? AND project_id = ?")) {
                update.setString(1, cipherText);
                update.setLong(2, now);
                update.setString(3, id);
                update.setString(4, tenantId);
                update.setString(5, projectId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalArgumentException("unknown secret " + id);
                }
            }
        });
    }

    public List<SecretSummary> list(String tenantId, String projectId) {
        List<RawSecret> rows;
        try {
            rows = read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, tenant_id, project_id, name, cipher_text FROM upstream_secret"
                                + " WHERE tenant_id = ? AND project_id = ? ORDER BY created_at, id")) {
                    select.setString(1, tenantId);
                    select.setString(2, projectId);
                    try (ResultSet result = select.executeQuery()) {
                        List<RawSecret> out = new ArrayList<>();
                        while (result.next()) {
                            out.add(new RawSecret(result.getString("id"), result.getString("tenant_id"),
                                    result.getString("project_id"), result.getString("name"),
                                    result.getString("cipher_text")));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to list provider secrets", failure);
        }
        List<SecretSummary> summaries = new ArrayList<>(rows.size());
        for (RawSecret row : rows) {
            String plaintext;
            try {
                plaintext = cipher.decrypt(row.cipherText());
            } catch (RootKeyMissingException missing) {
                throw missing;
            } catch (RuntimeException mismatch) {
                throw new RootKeyMismatchException(MISMATCH_MESSAGE, mismatch);
            }
            summaries.add(new SecretSummary(row.id(), row.tenantId(), row.projectId(), row.name(),
                    mask(plaintext)));
        }
        return summaries;
    }

    /**
     * A root key can encrypt regardless of the stored data, so a wrong key would silently
     * write secrets the correct key can never read. Probe one existing ciphertext first and
     * fail closed on mismatch.
     */
    private void requireUsableRootKey() {
        String existing;
        try {
            existing = read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT cipher_text FROM upstream_secret LIMIT 1");
                     ResultSet result = select.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to verify the provider-secret root key", failure);
        }
        if (existing == null) {
            return;
        }
        try {
            cipher.decrypt(existing);
        } catch (RootKeyMissingException missing) {
            throw missing;
        } catch (RuntimeException mismatch) {
            throw new RootKeyMismatchException(MISMATCH_MESSAGE, mismatch);
        }
    }

    public String resolve(String tenantId, String projectId, String id) {
        try {
            return database.read(connection -> {
                try (var s = connection.prepareStatement("SELECT cipher_text FROM upstream_secret WHERE id=? AND tenant_id=? AND project_id=?")) {
                    s.setString(1, id); s.setString(2, tenantId); s.setString(3, projectId);
                    try (var rows = s.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("unknown provider secret");
                        return cipher.decrypt(rows.getString(1));
                    }
                }
            });
        } catch (SQLException e) { throw new IllegalStateException("failed to resolve provider secret", e); }
    }

    private static String mask(String plaintext) {
        if (plaintext == null || plaintext.length() <= 4) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - 4);
    }

    private record RawSecret(String id, String tenantId, String projectId, String name, String cipherText) {
    }

    private void mutate(SqlBody body) {
        try {
            database.transaction(connection -> { body.run(connection); return null; });
        } catch (SQLException failure) {
            if (SqliteErrors.isStorageFull(failure)) throw new StorageLimitException("database or disk is full");
            throw new IllegalStateException("failed to persist provider secret", failure);
        }
    }

    private <T> T read(SqlRead<T> body) throws SQLException {
        return database.read(body::read);
    }

    @FunctionalInterface
    private interface SqlBody {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T read(Connection connection) throws SQLException;
    }
}
