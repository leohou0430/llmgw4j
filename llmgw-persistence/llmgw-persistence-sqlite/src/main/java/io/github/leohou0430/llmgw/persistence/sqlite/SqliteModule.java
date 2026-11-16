package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;

/**
 * Named {@code sqlite} extension module. Binds the default {@link DatabaseContext}
 * to a {@link SqliteContext} opened from {@link SqliteConfig}. The context owns the
 * instance and closes it on runtime shutdown (see {@code ExtensionContext.get}).
 */
public final class SqliteModule implements ExtensionModule {
    @Override
    public void configure(ExtensionContext context) {
        context.bindDefault(DatabaseContext.class, () -> {
            SqliteConfig config = context.get(SqliteConfig.class);
            try {
                return new SqliteContext(config.databaseFile(), config.busyTimeoutMillis());
            } catch (SQLException failure) {
                throw new IllegalStateException(
                        "failed to open SQLite database: " + config.databaseFile(), failure);
            }
        });
    }
}