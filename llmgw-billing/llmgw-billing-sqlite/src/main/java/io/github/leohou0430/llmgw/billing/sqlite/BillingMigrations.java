package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.persistence.sqlite.Migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * V10: billing schema per ADR-0007. All amounts are integer nano-CNY; ledger entries post
 * in whole micro-CNY (1000 nano) with a per-tenant carry column. Recharges are
 * voucher-idempotent via UNIQUE(tenant_id, voucher_no).
 */
public final class BillingMigrations {

	private static final List<String> BILLING_DDL = List.of(
			"CREATE TABLE billing_account (tenant_id TEXT PRIMARY KEY," + " currency TEXT NOT NULL DEFAULT 'CNY',"
					+ " balance_nano INTEGER NOT NULL DEFAULT 0," + " overdraft_nano INTEGER NOT NULL DEFAULT 0,"
					+ " carry_nano INTEGER NOT NULL DEFAULT 0 CHECK(carry_nano BETWEEN 0 AND 999),"
					+ " updated_at INTEGER NOT NULL)",
			"CREATE TABLE billing_recharge (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
					+ " amount_nano INTEGER NOT NULL, voucher_no TEXT NOT NULL,"
					+ " operator_id TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'recharge',"
					+ " related_id TEXT, created_at INTEGER NOT NULL," + " UNIQUE(tenant_id, voucher_no))",
			"CREATE TABLE billing_reservation (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
					+ " project_id TEXT NOT NULL, model TEXT NOT NULL,"
					+ " price_version INTEGER NOT NULL, amount_nano INTEGER NOT NULL,"
					+ " status TEXT NOT NULL, created_at INTEGER NOT NULL,"
					+ " settled_at INTEGER, settled_nano INTEGER, reason TEXT," + " operator_id TEXT)",
			"CREATE INDEX idx_reservation_tenant_status ON billing_reservation(tenant_id, status)",
			"CREATE TABLE price_version (model TEXT NOT NULL, version INTEGER NOT NULL,"
					+ " input_nano_per_million INTEGER NOT NULL," + " output_nano_per_million INTEGER NOT NULL,"
					+ " nano_per_image INTEGER NOT NULL DEFAULT 0," + " nano_per_task INTEGER NOT NULL DEFAULT 0,"
					+ " nano_per_second INTEGER NOT NULL DEFAULT 0,"
					+ " frozen_at INTEGER NOT NULL, PRIMARY KEY(model, version))",
			"CREATE TABLE task_billing_link (tenant_id TEXT NOT NULL,"
					+ " project_id TEXT NOT NULL, provider_source TEXT NOT NULL,"
					+ " upstream_task_id TEXT NOT NULL, billing_call_id TEXT NOT NULL,"
					+ " model TEXT NOT NULL, price_version INTEGER NOT NULL,"
					+ " status TEXT NOT NULL, created_at INTEGER NOT NULL,"
					+ " settled_at INTEGER, settled_nano INTEGER,"
					+ " PRIMARY KEY (tenant_id, project_id, provider_source, upstream_task_id))",
			"CREATE TABLE project_sublimit (project_id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
					+ " limit_nano INTEGER NOT NULL)",
			"CREATE TABLE billing_reservation_archive (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
					+ " project_id TEXT NOT NULL, model TEXT NOT NULL,"
					+ " price_version INTEGER NOT NULL, amount_nano INTEGER NOT NULL,"
					+ " status TEXT NOT NULL, created_at INTEGER NOT NULL,"
					+ " settled_at INTEGER, settled_nano INTEGER, reason TEXT,"
					+ " operator_id TEXT, archived_at INTEGER NOT NULL)",
			"CREATE TABLE billing_archive_watermark (id INTEGER PRIMARY KEY CHECK(id=1),"
					+ " high_water_mark INTEGER NOT NULL, digest TEXT NOT NULL,"
					+ " archived_count INTEGER NOT NULL, updated_at INTEGER NOT NULL)");

	private BillingMigrations() {
	}

	/**
	 * V10: the billing schema (accounts, recharges, reservations, prices, sub-limits).
	 */
	public static List<Migration> billing() {
		return List.of(new Migration() {
			@Override
			public int version() {
				return 10;
			}

			@Override
			public void apply(Connection connection) throws SQLException {
				for (String statement : BILLING_DDL) {
					try (Statement jdbc = connection.createStatement()) {
						jdbc.execute(statement);
					}
				}
			}
		});
	}

}
