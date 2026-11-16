package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Consistent online backup of a live SQLite database. {@code VACUUM INTO} writes a
 * transactionally-consistent snapshot (safe while the database is live, WAL included) to a
 * new file; the resulting file is a normal, restorable SQLite database. Restore is opening
 * the backup as a DatabaseContext. (OS-level kill / real full-disk / root-key-missing tests
 * are separate ops experiments; not claimed here.)
 */
public final class OnlineBackup {

    private OnlineBackup() {
    }

    public static void backup(DatabaseContext source, Path targetFile) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetFile, "targetFile");
        String escaped = targetFile.toAbsolutePath().toString().replace("'", "''");
        try {
            source.read(connection -> {
                if (!connection.getAutoCommit()) throw new SQLException("backup requires an idle transaction boundary");
                try (Statement statement = connection.createStatement()) {
                    statement.execute("VACUUM INTO '" + escaped + "'");
                }
                return null;
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("online backup failed: " + targetFile, failure);
        }
    }
}
