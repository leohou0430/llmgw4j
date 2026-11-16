package io.github.leohou0430.llmgw.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
 * T3.10 seam: retention purges only old tombstones and old audit rows (never active
 * resources, never recent audit); the storage guard rejects writes when the database
 * exceeds its cap or free disk falls under the margin; and data survives a close/reopen
 * on a temporary DB (WAL durability).
 */
class RetentionAndStorageTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long NOW = 1_000_000_000_000L;

    @TempDir
    Path tempDir;

    private SqliteContext migrated(String name) throws Exception {
        SqliteContext database = new SqliteContext(tempDir.resolve(name), 1000L);
        new SchemaMigrator(database).migrate(Migrations.all());
        return database;
    }

    private static void insertAudit(Connection connection, String id, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_event (id, project_id, action, created_at, detail) VALUES (?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, "p1");
            statement.setString(3, "test");
            statement.setLong(4, createdAt);
            statement.setString(5, "d");
            statement.executeUpdate();
        }
    }

    private static void insertResource(Connection connection, String id, boolean deleted, long createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO resource_catalog (id, project_id, type, upstream_id, source_route, provider,"
                        + " config_version, created_at, observable_status, metadata, last_synced_at, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, "p1");
            statement.setString(3, "batch");
            statement.setString(4, "up");
            statement.setString(5, "route-a");
            statement.setString(6, "deepseek");
            statement.setString(7, "cfg-1");
            statement.setLong(8, createdAt);
            statement.setString(9, "pending");
            statement.setString(10, "{}");
            statement.setLong(11, 0L);
            statement.setInt(12, deleted ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static boolean exists(Connection connection, String table, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Test
    void retentionPurgesOnlyOldTombstonesAndOldAudit() throws Exception {
        try (SqliteContext database = migrated("retention.db")) {
            Connection connection = database.connection();
            long old = NOW - 40 * DAY_MS;
            long recent = NOW - 1 * DAY_MS;
            insertAudit(connection, "a-old", old);
            insertAudit(connection, "a-recent", recent);
            insertResource(connection, "r-old", true, old);
            insertResource(connection, "r-recent", true, recent);
            insertResource(connection, "r-active", false, old);

            int purged = RetentionPolicy.defaults().purge(database, NOW);

            assertTrue(purged >= 2);
            assertFalse(exists(connection, "audit_event", "a-old"));
            assertTrue(exists(connection, "audit_event", "a-recent"));
            assertFalse(exists(connection, "resource_catalog", "r-old"));
            assertTrue(exists(connection, "resource_catalog", "r-recent"));
            assertTrue(exists(connection, "resource_catalog", "r-active"), "active resources are never purged");
        }
    }

    @Test
    void storageGuardRejectsWhenDatabaseExceedsLimit() throws Exception {
        Path file = tempDir.resolve("quota.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            new SchemaMigrator(database).migrate(Migrations.all());
        }
        long size = Files.size(file);
        assertThrows(StorageLimitException.class,
                () -> new StorageGuard(size - 1, 0, file).requireWritable());
    }

    @Test
    void storageGuardRejectsWhenFreeDiskBelowMargin() throws Exception {
        Path file = tempDir.resolve("disk.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            // ensures the file exists
        }
        assertThrows(StorageLimitException.class,
                () -> new StorageGuard(Long.MAX_VALUE, Long.MAX_VALUE, file).requireWritable());
    }

    @Test
    void storageGuardAllowsWhenUnderLimits() throws Exception {
        Path file = tempDir.resolve("ok.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            // ensures the file exists
        }
        new StorageGuard(Long.MAX_VALUE, 0, file).requireWritable();
    }

    @Test
    void dataSurvivesCloseAndReopen() throws Exception {
        Path file = tempDir.resolve("durability.db");
        try (SqliteContext database = new SqliteContext(file, 1000L)) {
            try (Statement statement = database.connection().createStatement()) {
                statement.execute("CREATE TABLE t (v INTEGER)");
                statement.execute("INSERT INTO t VALUES (7)");
            }
        }
        try (SqliteContext reopened = new SqliteContext(file, 1000L);
             Statement statement = reopened.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT v FROM t")) {
            assertTrue(rows.next());
            assertEquals(7, rows.getInt(1));
        }
    }
}