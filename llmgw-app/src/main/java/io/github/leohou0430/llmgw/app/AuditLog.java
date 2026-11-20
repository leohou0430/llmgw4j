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
 * Management audit trail backed by the {@code audit_event} table. Successful
 * control-plane changes are recorded here (action + non-secret detail); reads are scoped
 * by project. The shared {@link DatabaseContext} connection is owned by the context and
 * never closed here.
 */
public final class AuditLog {

	public record Entry(String actorId, String action, long createdAt, String detail) {
	}

	private final DatabaseContext database;

	public AuditLog(DatabaseContext database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	public <T> T change(String actor, String tenant, String project, String action, String detail,
			java.util.function.Supplier<T> mutation) {
		try {
			return database.transaction(connection -> {
				T result = mutation.get();
				record(actor, tenant, project, action, detail);
				return result;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("failed to commit audited change", failure);
		}
	}

	public void record(String projectId, String action, String detail) {
		record("", "", projectId, action, detail);
	}

	public void record(String actor, String tenant, String project, String action, String detail) {
		try {
			database.transaction(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO audit_event (id, actor_id, tenant_id, project_id, action, created_at, detail) VALUES (?,?,?,?,?,?,?)")) {
					statement.setString(1, UUID.randomUUID().toString());
					statement.setString(2, actor);
					statement.setString(3, tenant);
					statement.setString(4, project);
					statement.setString(5, action);
					statement.setLong(6, System.currentTimeMillis());
					statement.setString(7, detail);
					statement.executeUpdate();
				}
				return null;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("failed to record audit event", failure);
		}
	}

	public List<Entry> recent(String tenantId, String projectId, int limit) {
		List<Entry> entries = new ArrayList<>();
		try {
			return database.read(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT actor_id,action,created_at,detail FROM audit_event WHERE tenant_id = ? AND project_id = ? ORDER BY created_at DESC LIMIT ?")) {
					statement.setString(1, tenantId);
					statement.setString(2, projectId);
					statement.setInt(3, Math.max(1, Math.min(limit, 200)));
					try (ResultSet rows = statement.executeQuery()) {
						while (rows.next())
							entries.add(new Entry(rows.getString("actor_id"), rows.getString("action"),
									rows.getLong("created_at"), rows.getString("detail")));
					}
				}
				return entries;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("failed to read audit", failure);
		}
	}

	public List<Entry> recent(String projectId, int limit) {
		List<Entry> entries = new ArrayList<>();
		try {
			database.read(connection -> {
				PreparedStatement statement = connection
					.prepareStatement("SELECT action, created_at, detail FROM audit_event WHERE project_id = ?"
							+ " ORDER BY created_at DESC LIMIT ?");
				try {
					statement.setString(1, projectId);
					statement.setInt(2, limit);
					try (ResultSet rows = statement.executeQuery()) {
						while (rows.next()) {
							entries.add(new Entry("", rows.getString("action"), rows.getLong("created_at"),
									rows.getString("detail")));
						}
					}
				}
				finally {
					statement.close();
				}

				return null;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("failed to read audit events", failure);
		}
		return entries;
	}

}
