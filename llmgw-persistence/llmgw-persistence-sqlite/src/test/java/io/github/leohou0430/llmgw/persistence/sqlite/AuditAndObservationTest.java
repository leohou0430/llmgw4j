package io.github.leohou0430.llmgw.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.9 seam: a control-plane change and its management-audit row commit in one
 * transaction — if the audit write fails the change is rejected/rolled back; a bounded
 * observation queue drops and counts overflow instead of growing without bound.
 */
class AuditAndObservationTest {

    @TempDir
    Path tempDir;

    private SqliteContext migrated(String name) throws Exception {
        SqliteContext database = new SqliteContext(tempDir.resolve(name), 1000L);
        new SchemaMigrator(database).migrate(Migrations.all());
        return database;
    }

    private static boolean rowExists(Connection connection, String table, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void insertResource(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO resource_catalog (id, project_id, type, upstream_id, source_route, provider,"
                        + " config_version, created_at, observable_status, metadata, last_synced_at, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0)")) {
            statement.setString(1, id);
            statement.setString(2, "p1");
            statement.setString(3, "batch");
            statement.setString(4, "up");
            statement.setString(5, "route-a");
            statement.setString(6, "deepseek");
            statement.setString(7, "cfg-1");
            statement.setLong(8, 1000L);
            statement.setString(9, "pending");
            statement.setString(10, "{}");
            statement.setLong(11, 0L);
            statement.executeUpdate();
        }
    }

    @Test
    void changeAndAuditCommitTogether() throws Exception {
        try (SqliteContext database = migrated("together.db")) {
            AuditedChange.applyAndAudit(database, "p1", "resource.create", "detail", connection -> insertResource(connection, "r1"));
            assertTrue(rowExists(database.connection(), "resource_catalog", "r1"));
            try (Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM audit_event WHERE action='resource.create'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void auditFailureRollsBackTheChange() throws Exception {
        try (SqliteContext database = migrated("rollback.db")) {
            try (Statement statement = database.connection().createStatement()) {
                statement.execute("DROP TABLE audit_event");
            }
            assertThrows(IllegalStateException.class, () -> AuditedChange.applyAndAudit(
                    database, "p1", "resource.create", "detail", connection -> insertResource(connection, "r1")));
            assertFalse(rowExists(database.connection(), "resource_catalog", "r1"),
                    "the change must be rolled back when its audit row cannot be written");
        }
    }

    @Test
    void boundedQueueDropsAndCountsOverflow() {
        BoundedObservationQueue queue = new BoundedObservationQueue(2);
        assertTrue(queue.offer("e1"));
        assertTrue(queue.offer("e2"));
        assertFalse(queue.offer("e3"), "overflow is dropped, not buffered");
        assertEquals(1, queue.dropped());
    }
}