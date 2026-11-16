package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.1 seam: named discovery of the {@code sqlite} module through the descriptor,
 * default binding of {@link DatabaseContext}, and resource ownership — the
 * {@link ExtensionContext} must close the instance it resolved when it closes.
 */
class SqliteModuleTest {

    @TempDir
    Path tempDir;

    @Test
    void namedModuleBindsDatabaseContextAndContextOwnsTheInstance() throws Exception {
        Path file = tempDir.resolve("module.db");
        Connection captured;
        try (ExtensionContext context = new ExtensionContext()) {
            context.bind(SqliteConfig.class, () -> new SqliteConfig(file, 2000L));
            context.loadModule("sqlite");
            context.seal();

            DatabaseContext database = context.get(DatabaseContext.class);
            assertInstanceOf(SqliteContext.class, database);
            captured = database.connection();
            assertFalse(captured.isClosed());
        }
        assertTrue(captured.isClosed(),
                "ExtensionContext must close the owned SqliteContext on close()");
    }
}