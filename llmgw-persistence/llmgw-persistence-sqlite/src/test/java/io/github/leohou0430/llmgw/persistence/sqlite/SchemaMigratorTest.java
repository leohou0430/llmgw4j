package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.2 seam: versioned, ordered, transactional schema migrations. Every assertion
 * uses independent truth — the version the migrator reports, {@code sqlite_master}
 * table presence, and a deliberately failing step — never migrator internals.
 */
class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Migration creating(int version, String table) {
        return new Migration() {
            @Override public int version() { return version; }
            @Override public void apply(Connection connection) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
                }
            }
        };
    }

    @Test
    void appliesFoundationalSchemaAndRecordsVersion() throws Exception {
        Path file = tempDir.resolve("schema.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            SchemaMigrator migrator = new SchemaMigrator(database);
            assertEquals(1, migrator.migrate(Migrations.foundational()));
            assertEquals(1, migrator.currentVersion());

            Connection connection = database.connection();
            for (String table : List.of("tenant", "project", "api_key_digest", "resource_catalog", "audit_event")) {
                assertTrue(tableExists(connection, table), "missing table: " + table);
            }
        }
    }

    @Test
    void upgradesAnExistingV1DatabaseToTheLatestVersionWithoutLosingData() throws Exception {
        Path file = tempDir.resolve("upgrade.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            SchemaMigrator migrator = new SchemaMigrator(database);
            assertEquals(1, migrator.migrate(Migrations.foundational()));

            Connection connection = database.connection();
            try (Statement insert = connection.createStatement()) {
                insert.execute("INSERT INTO tenant (id, name, created_at) VALUES ('t-old', 'legacy', 1)");
                insert.execute("INSERT INTO resource_catalog (id, project_id, type, upstream_id, source_route,"
                        + " provider, config_version, created_at, observable_status, deleted)"
                        + " VALUES ('r-old', 'p1', 'file', 'up-1', 'route-a', 'deepseek', 'v1', 1, 'ready', 0)");
            }

            assertEquals(9, migrator.migrate(Migrations.all()), "upgrade reaches the latest schema version");
            assertEquals(9, migrator.currentVersion());

            for (String table : List.of("key_store", "route_version", "account", "session", "upstream_secret")) {
                assertTrue(tableExists(connection, table), "missing post-upgrade table: " + table);
            }
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT name FROM tenant WHERE id = 't-old'")) {
                assertTrue(rows.next(), "pre-upgrade tenant row must survive");
                assertEquals("legacy", rows.getString(1));
            }
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT id FROM resource_catalog WHERE id = 'r-old'")) {
                assertTrue(rows.next(), "pre-upgrade catalog row must survive");
            }
        }
    }

    @Test
    void repeatedMigrateIsIdempotent() throws Exception {
        Path file = tempDir.resolve("idempotent.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            SchemaMigrator migrator = new SchemaMigrator(database);
            assertEquals(1, migrator.migrate(Migrations.foundational()));
            assertEquals(1, migrator.migrate(Migrations.foundational()));
            assertEquals(1, migrator.currentVersion());
        }
    }

    @Test
    void appliesNewerMigrationsOnlyWhenPresent() throws Exception {
        Path file = tempDir.resolve("incremental.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            SchemaMigrator migrator = new SchemaMigrator(database);
            assertEquals(1, migrator.migrate(Migrations.foundational()));
            List<Migration> withV2 = List.of(Migrations.foundational().get(0), creating(2, "later"));
            assertEquals(2, migrator.migrate(withV2));
            assertTrue(tableExists(database.connection(), "later"));
        }
    }

    @Test
    void rollsBackFailedMigrationAndKeepsPreviousVersion() throws Exception {
        Path file = tempDir.resolve("rollback.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            SchemaMigrator migrator = new SchemaMigrator(database);
            assertEquals(1, migrator.migrate(Migrations.foundational()));

            Migration failing = new Migration() {
                @Override public int version() { return 2; }
                @Override public void apply(Connection connection) throws SQLException {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE partial (id INTEGER PRIMARY KEY)");
                    }
                    throw new SQLException("boom");
                }
            };
            List<Migration> withFailing = List.of(Migrations.foundational().get(0), failing);
            assertThrows(SQLException.class, () -> migrator.migrate(withFailing));

            assertEquals(1, migrator.currentVersion(), "failed step must not advance the version");
            assertFalse(tableExists(database.connection(), "partial"),
                    "failed step's DDL must be rolled back");
        }
    }
}
