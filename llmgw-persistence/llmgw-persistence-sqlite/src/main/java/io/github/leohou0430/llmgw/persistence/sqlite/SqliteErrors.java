package io.github.leohou0430.llmgw.persistence.sqlite;

import java.sql.SQLException;
import java.util.Locale;

/** Classifies SQLite driver failures that mean the database/disk ran out of space. */
public final class SqliteErrors {

    private SqliteErrors() {
    }

    public static boolean isStorageFull(SQLException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                if ("SQLITE_FULL".equals(sql.getSQLState())) {
                    return true;
                }
                String message = sql.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains("database or disk is full")) {
                    return true;
                }
                for (SQLException next = sql.getNextException(); next != null; next = next.getNextException()) {
                    if (isStorageFull(next)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
