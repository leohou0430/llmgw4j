package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Applies a control-plane change and its management-audit row in a single transaction. If
 * either the change or the audit insert fails, the whole transaction rolls back and the
 * change is rejected — so a config/resource change never lands without its audit trail.
 */
public final class AuditedChange {

	@FunctionalInterface
	public interface ChangeBody {

		void apply(Connection connection) throws SQLException;

	}

	private AuditedChange() {
	}

	public static void applyAndAudit(DatabaseContext database, String projectId, String action, String detail,
			ChangeBody change) {
		try {
			database.transaction(connection -> {
				change.apply(connection);
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO audit_event (id, project_id, action, created_at, detail) VALUES (?,?,?,?,?)")) {
					statement.setString(1, UUID.randomUUID().toString());
					statement.setString(2, projectId);
					statement.setString(3, action);
					statement.setLong(4, System.currentTimeMillis());
					statement.setString(5, detail);
					statement.executeUpdate();
				}
				return null;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("change rejected: change/audit failed", failure);
		}
	}

}
