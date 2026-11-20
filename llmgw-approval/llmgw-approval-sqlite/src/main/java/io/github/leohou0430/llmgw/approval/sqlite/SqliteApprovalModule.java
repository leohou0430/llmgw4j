package io.github.leohou0430.llmgw.approval.sqlite;

import io.github.leohou0430.llmgw.approval.ApprovalService;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

/**
 * Named {@code approval-sqlite} extension module. Binds the approval workflow to the
 * shared SQLite {@link DatabaseContext}; resolves a module-scoped instance and never
 * holds static state. The definition is a default: the composition root may
 * {@code replace} it before seal. Schema creation uses
 * {@link ApprovalMigrations#approval()} applied by the caller's migrator (same
 * deferred-holder style as the billing pack).
 */
public final class SqliteApprovalModule implements ExtensionModule {

	@Override
	public void configure(ExtensionContext context) {
		context.bindDefault(ApprovalService.class, () -> {
			DatabaseContext database = context.get(DatabaseContext.class);
			return new SqliteApprovalService(database);
		});
	}

}
