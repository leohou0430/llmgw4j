package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

/**
 * Named {@code billing-sqlite} extension module. Binds the billing ledger, price book,
 * per-protocol meter and the async-task billing link to the shared SQLite
 * {@link DatabaseContext}; all resolve module-scoped instances and never hold static
 * state. Definitions are defaults: the composition root may {@code replace} them before
 * seal.
 */
public final class BillingSqliteModule implements ExtensionModule {

	@Override
	public void configure(ExtensionContext context) {
		context.bindDefault(BillingLedger.class, () -> {
			DatabaseContext database = context.get(DatabaseContext.class);
			return new SqliteBillingLedger(database);
		});
		context.bindDefault(PriceBook.class, () -> {
			DatabaseContext database = context.get(DatabaseContext.class);
			return new SqlitePriceBook(database);
		});
		context.bindDefault(BillingMeter.class, DefaultBillingMeter::new);
		context.bindDefault(SqliteTaskBillingLink.class, () -> {
			DatabaseContext database = context.get(DatabaseContext.class);
			return new SqliteTaskBillingLink(database);
		});
	}

}
