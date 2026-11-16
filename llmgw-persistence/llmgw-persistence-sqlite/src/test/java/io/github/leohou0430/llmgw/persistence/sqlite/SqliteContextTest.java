package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.1 seam: the public {@link SqliteContext} lifecycle and configuration contract.
 * Every assertion reads behaviour through {@link DatabaseContext#connection()} or
 * {@link DatabaseContext#close()} — never internals. Expected values are independent
 * literals ("wal", the busy-timeout we pass, the row we inserted), not recomputed.
 */
class SqliteContextTest {

    @TempDir
    Path tempDir;

    private static String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(rows.next(), "PRAGMA " + name + " returned no row");
            return rows.getString(1);
        }
    }

    @Test
    void appliesWalJournalModeAndConfiguredBusyTimeout() throws Exception {
        Path file = tempDir.resolve("wal.db");
        try (SqliteContext context = new SqliteContext(file, 4321L)) {
            assertEquals("wal", pragma(context.connection(), "journal_mode"));
            assertEquals("4321", pragma(context.connection(), "busy_timeout"));
        }
    }

    @Test
    void enablesForeignKeys() throws Exception {
        Path file = tempDir.resolve("fk.db");
        try (SqliteContext context = new SqliteContext(file, 1000L)) {
            assertEquals("1", pragma(context.connection(), "foreign_keys"));
        }
    }

    @Test
    void roundTripsRowsThroughTheConnection() throws Exception {
        Path file = tempDir.resolve("roundtrip.db");
        try (SqliteContext context = new SqliteContext(file, 1000L)) {
            Connection connection = context.connection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (value INTEGER NOT NULL)");
                statement.execute("INSERT INTO probe (value) VALUES (42)");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT value FROM probe")) {
                assertTrue(rows.next());
                assertEquals(42, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void closeReleasesTheConnection() throws Exception {
        Path file = tempDir.resolve("release.db");
        SqliteContext context = new SqliteContext(file, 1000L);
        Connection connection = context.connection();
        assertFalse(connection.isClosed());
        context.close();
        assertTrue(connection.isClosed());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path file = tempDir.resolve("idempotent.db");
        SqliteContext context = new SqliteContext(file, 1000L);
        context.close();
        context.close();
    }

    @Test
    void separateFilesAreIsolated() throws Exception {
        Path fileA = tempDir.resolve("a.db");
        Path fileB = tempDir.resolve("b.db");
        try (SqliteContext a = new SqliteContext(fileA, 1000L);
             SqliteContext b = new SqliteContext(fileB, 1000L)) {
            try (Statement statement = a.connection().createStatement()) {
                statement.execute("CREATE TABLE only_in_a (value INTEGER)");
                statement.execute("INSERT INTO only_in_a (value) VALUES (7)");
            }
            try (Statement statement = b.connection().createStatement()) {
                assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT value FROM only_in_a"));
            }
        }
    }
}