package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.SecretCipher;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

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
            summaries.add(new SecretSummary(row.id(), row.tenantId(), row.projectId(), row.name(),
                    mask(cipher.decrypt(row.cipherText()))));
        }
        return summaries;
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
        Connection connection;
        try {
            connection = database.connection();
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to open secret-store connection", failure);
        }
        synchronized (connection) {
            boolean previousAutoCommit;
            try {
                previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to begin secret transaction", failure);
            }
            try {
                body.run(connection);
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    failure.addSuppressed(ignored);
                }
                throw new IllegalStateException("failed to persist provider secret", failure);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // transaction already finished
                }
            }
        }
    }

    private <T> T read(SqlRead<T> body) throws SQLException {
        Connection connection = database.connection();
        synchronized (connection) {
            return body.read(connection);
        }
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
