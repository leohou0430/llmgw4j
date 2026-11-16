package io.github.leohou0430.llmgw.persistence.sqlite;

import java.nio.file.Path;

/** Configuration bound before the named {@code sqlite} module opens a database. */
public record SqliteConfig(Path databaseFile, long busyTimeoutMillis) {
}