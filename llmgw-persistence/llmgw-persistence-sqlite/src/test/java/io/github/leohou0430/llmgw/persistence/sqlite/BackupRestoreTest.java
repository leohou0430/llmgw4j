package io.github.leohou0430.llmgw.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.11 seam (testable part): a consistent online backup of a live WAL database captures
 * both data and schema, and the backup opens as a valid, restorable database.
 */
class BackupRestoreTest {

    @TempDir
    Path tempDir;

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
    void onlineBackupThenRestorePreservesDataAndSchema() throws Exception {
        Path liveFile = tempDir.resolve("live.db");
        Path backupFile = tempDir.resolve("backup.db");

        try (SqliteContext live = new SqliteContext(liveFile, 1000L)) {
            new SchemaMigrator(live).migrate(Migrations.all());
            insertResource(live.connection(), "r1");
            OnlineBackup.backup(live, backupFile);
        }

        assertTrue(Files.exists(backupFile), "backup file must be created");
        try (SqliteContext restored = new SqliteContext(backupFile, 1000L)) {
            assertTrue(rowExists(restored.connection(), "resource_catalog", "r1"), "data survives into the backup");
            assertEquals(6, new SchemaMigrator(restored).currentVersion(), "schema version survives into the backup");
        }
    }
}