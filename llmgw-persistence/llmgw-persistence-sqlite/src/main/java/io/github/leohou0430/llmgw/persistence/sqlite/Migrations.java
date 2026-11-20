package io.github.leohou0430.llmgw.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, immutable schema catalog. Version 1 creates the foundational identity /
 * catalog / audit tables; version 2 adds the runtime key-store tables (keyed by the
 * at-rest token, with allowed-model and metadata children). The raw API key is never
 * stored — only its at-rest form (a SHA-256 digest when hashing is on). Later versions
 * append their tables as this list grows.
 */
public final class Migrations {

	private static final List<String> FOUNDATIONAL_DDL = List.of(
			"CREATE TABLE tenant (id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL)",
			"CREATE TABLE project (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, name TEXT NOT NULL,"
					+ " created_at INTEGER NOT NULL)",
			"CREATE TABLE api_key_digest (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, digest TEXT NOT NULL,"
					+ " algorithm TEXT NOT NULL, rotated_to TEXT, legacy_until INTEGER, enabled INTEGER NOT NULL,"
					+ " created_at INTEGER NOT NULL)",
			"CREATE TABLE resource_catalog (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, type TEXT NOT NULL,"
					+ " upstream_id TEXT NOT NULL, source_route TEXT NOT NULL, provider TEXT NOT NULL,"
					+ " config_version TEXT NOT NULL, created_at INTEGER NOT NULL, observable_status TEXT NOT NULL,"
					+ " metadata TEXT, last_synced_at INTEGER, deleted INTEGER NOT NULL DEFAULT 0)",
			"CREATE TABLE audit_event (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, action TEXT NOT NULL,"
					+ " created_at INTEGER NOT NULL, detail TEXT)");

	private static final List<String> KEY_STORE_DDL = List.of(
			"CREATE TABLE key_store (at_rest TEXT PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL,"
					+ " quota_tokens INTEGER NOT NULL, expires_at_epoch_ms INTEGER NOT NULL,"
					+ " group_name TEXT NOT NULL, budget_micro_usd INTEGER NOT NULL, rotated_to TEXT,"
					+ " legacy_until INTEGER NOT NULL DEFAULT 0)",
			"CREATE TABLE key_allowed_model (at_rest TEXT NOT NULL, model TEXT NOT NULL,"
					+ " PRIMARY KEY (at_rest, model))",
			"CREATE TABLE key_metadata (at_rest TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL,"
					+ " PRIMARY KEY (at_rest, k))");

	private static final List<String> ROUTE_VERSION_DDL = List
		.of("CREATE TABLE route_version (version INTEGER PRIMARY KEY, config TEXT NOT NULL,"
				+ " base_version INTEGER NOT NULL, created_at INTEGER NOT NULL)");

	private static final List<String> ACCOUNT_DDL = List
		.of("CREATE TABLE account (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE,"
				+ " password_hash TEXT NOT NULL, role TEXT NOT NULL, tenant_id TEXT NOT NULL,"
				+ " project_id TEXT NOT NULL, enabled INTEGER NOT NULL, created_at INTEGER NOT NULL)");

	private static final List<String> SESSION_DDL = List
		.of("CREATE TABLE session (id TEXT PRIMARY KEY, account_id TEXT NOT NULL,"
				+ " expires_at_epoch_ms INTEGER NOT NULL, token_version INTEGER NOT NULL,"
				+ " created_at INTEGER NOT NULL)");

	private static final List<String> UPSTREAM_SECRET_DDL = List
		.of("CREATE TABLE upstream_secret (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
				+ " project_id TEXT NOT NULL, name TEXT NOT NULL, cipher_text TEXT NOT NULL,"
				+ " rotated_from TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");

	private Migrations() {
	}

	/** V1 only: the foundational identity/catalog/audit schema. */
	public static List<Migration> foundational() {
		return List.of(ddl(1, FOUNDATIONAL_DDL));
	}

	/** The full ordered migration set: V1 foundational … V10 billing (ADR-0007). */
	public static List<Migration> all() {
		List<Migration> base = List.of(ddl(1, FOUNDATIONAL_DDL), ddl(2, KEY_STORE_DDL), ddl(3, ROUTE_VERSION_DDL),
				ddl(4, ACCOUNT_DDL), ddl(5, SESSION_DDL), ddl(6, UPSTREAM_SECRET_DDL),
				ddl(7, List.of("ALTER TABLE audit_event ADD COLUMN actor_id TEXT NOT NULL DEFAULT ''",
						"ALTER TABLE audit_event ADD COLUMN tenant_id TEXT NOT NULL DEFAULT ''",
						"ALTER TABLE resource_catalog ADD COLUMN creation_seq INTEGER NOT NULL DEFAULT 0",
						"UPDATE resource_catalog SET creation_seq=rowid WHERE creation_seq=0",
						"CREATE TABLE catalog_sequence(id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)",
						"INSERT INTO catalog_sequence(id,value) SELECT 1,COALESCE(MAX(creation_seq),0) FROM resource_catalog",
						"CREATE INDEX idx_resource_creation ON resource_catalog(project_id,creation_seq)")),
				ddl(8, SqliteUsageLedger.SCHEMA),
				ddl(9, List.of("CREATE TABLE runtime_bootstrap (name TEXT PRIMARY KEY)")),
				ddl(11, List.of(
						"CREATE TABLE payload_record (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
								+ " project_id TEXT NOT NULL, request_body TEXT NOT NULL, response_body TEXT NOT NULL,"
								+ " structured TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,"
								+ " truncated INTEGER NOT NULL DEFAULT 0)",
						"CREATE INDEX idx_payload_expiry ON payload_record(expires_at)",
						"CREATE INDEX idx_payload_project_time ON payload_record(project_id, created_at)")));
		List<Migration> all = new ArrayList<>(base);
		all.addAll(BillingMigrationsHolder.V10);
		all.addAll(IdempotencyMigrationsHolder.V12);
		all.addAll(ApprovalMigrationsHolder.V13);
		return List.copyOf(all);
	}

	/**
	 * Deferred holder so the persistence module stays free of billing imports at compile
	 * time.
	 */
	/**
	 * Deferred holder so the persistence module stays free of billing imports at compile
	 * time.
	 */
	private static final class BillingMigrationsHolder {

		private static final List<Migration> V10 = loadBilling();

		private static List<Migration> loadBilling() {
			try {
				Class<?> holder = Class.forName("io.github.leohou0430.llmgw.billing.sqlite.BillingMigrations");
				Object migrations = holder.getMethod("billing").invoke(null);
				@SuppressWarnings("unchecked")
				List<Migration> cast = (List<Migration>) migrations;
				return cast;
			}
			catch (ClassNotFoundException absent) {
				// Billing pack not on the classpath: skip V10 (the ledger SPI is absent
				// too).
				return List.of();
			}
			catch (ReflectiveOperationException e) {
				throw new IllegalStateException("failed to load billing migrations", e);
			}
		}

	}

	/** Deferred holder: V12 customer idempotency keys (Chapter-9 §9.4.5) when present. */
	private static final class IdempotencyMigrationsHolder {

		private static final List<Migration> V12 = load("idempotency",
				"io.github.leohou0430.llmgw.idempotency.sqlite.IdempotencyMigrations");

		private static List<Migration> load(String label, String className) {
			try {
				Class<?> holder = Class.forName(className);
				Object migrations = holder.getMethod(label).invoke(null);
				@SuppressWarnings("unchecked")
				List<Migration> cast = (List<Migration>) migrations;
				return cast;
			}
			catch (ClassNotFoundException absent) {
				return List.of();
			}
			catch (ReflectiveOperationException e) {
				throw new IllegalStateException("failed to load " + label + " migrations", e);
			}
		}

	}

	/** Deferred holder: V13 approval workflow (Chapter-9 §9.5.4) when present. */
	private static final class ApprovalMigrationsHolder {

		private static final List<Migration> V13 = IdempotencyMigrationsHolder.load("approval",
				"io.github.leohou0430.llmgw.approval.sqlite.ApprovalMigrations");

	}

	private static Migration ddl(int version, List<String> statements) {
		return new Migration() {
			@Override
			public int version() {
				return version;
			}

			@Override
			public void apply(Connection connection) throws SQLException {
				for (String statement : statements) {
					try (Statement jdbc = connection.createStatement()) {
						jdbc.execute(statement);
					}
				}
			}
		};
	}

}
