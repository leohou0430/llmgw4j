package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Management audit trail backed by the {@code audit_event} table. Successful control-plane
 * changes are recorded here (action + non-secret detail); reads are scoped by project. The
 * shared {@link DatabaseContext} connection is owned by the context and never closed here.
 */
public final class AuditLog {
    public record Entry(String action, long createdAt, String detail) {
    }

    private final DatabaseContext database;

    public AuditLog(DatabaseContext database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void record(String projectId, String action, String detail) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "INSERT INTO audit_event (id, project_id, action, created_at, detail) VALUES (?,?,?,?,?)");
            try {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, projectId);
                statement.setString(3, action);
                statement.setLong(4, System.currentTimeMillis());
                statement.setString(5, detail);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to record audit event", failure);
        }
    }

    public List<Entry> recent(String projectId, int limit) {
        List<Entry> entries = new ArrayList<>();
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT action, created_at, detail FROM audit_event WHERE project_id = ?"
                            + " ORDER BY created_at DESC LIMIT ?");
            try {
                statement.setString(1, projectId);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        entries.add(new Entry(rows.getString("action"), rows.getLong("created_at"),
                                rows.getString("detail")));
                    }
                }
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read audit events", failure);
        }
        return entries;
    }
}