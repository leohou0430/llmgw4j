package io.github.leohou0430.llmgw.idempotency.sqlite;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.idempotency.IdempotencyStore;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

/**
 * Named {@code idempotency-sqlite} extension module. Binds the customer idempotency-key
 * store to the shared SQLite {@link DatabaseContext}; resolves a module-scoped instance
 * and never holds static state. The definition is a default: the composition root may
 * {@code replace} it before seal. Schema creation uses
 * {@link IdempotencyMigrations#idempotency()} applied by the caller's migrator (same
 * deferred-holder style as the billing pack).
 */
public final class SqliteIdempotencyModule implements ExtensionModule {

	@Override
	public void configure(ExtensionContext context) {
		context.bindDefault(IdempotencyStore.class, () -> {
			DatabaseContext database = context.get(DatabaseContext.class);
			return new SqliteIdempotencyStore(database);
		});
	}

}
