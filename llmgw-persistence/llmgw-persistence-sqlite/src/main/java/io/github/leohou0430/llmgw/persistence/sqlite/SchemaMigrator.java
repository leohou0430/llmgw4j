package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Applies versioned schema migrations in ascending order against a single
 * {@link DatabaseContext}. The recorded {@code schema_version} only advances when a whole
 * step commits; a failing step is rolled back so its partial DDL never lands. Re-running
 * with already-applied steps is a no-op.
 */
public final class SchemaMigrator {

	private final DatabaseContext database;

	public SchemaMigrator(DatabaseContext database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Applies every migration with a version above the recorded one; returns the
	 * resulting version.
	 */
	public int migrate(List<Migration> migrations) throws SQLException {
		Objects.requireNonNull(migrations, "migrations");
		database.read(connection -> {
			ensureVersionTable(connection);
			return null;
		});
		int current = currentVersion();
		List<Migration> pending = migrations.stream()
			.filter(m -> m.version() > current)
			.sorted(Comparator.comparingInt(Migration::version))
			.toList();
		for (Migration migration : pending) {
			database.transaction(connection -> {
				migration.apply(connection);
				writeVersion(connection, migration.version());
				return null;
			});
		}
		return currentVersion();
	}

	public int currentVersion() throws SQLException {
		return database.read(connection -> {
			ensureVersionTable(connection);
			return readVersion(connection);
		});
	}

	private static void ensureVersionTable(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
		}
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM schema_version")) {
			rows.next();
			if (rows.getInt(1) == 0) {
				try (Statement insert = connection.createStatement()) {
					insert.execute("INSERT INTO schema_version (version) VALUES (0)");
				}
			}
		}
	}

	private static int readVersion(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
			rows.next();
			return rows.getInt(1);
		}
	}

	private static void writeVersion(Connection connection, int version) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("UPDATE schema_version SET version = " + version);
		}
	}

}
