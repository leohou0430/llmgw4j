package io.github.leohou0430.llmgw.idempotency.sqlite;

import io.github.leohou0430.llmgw.persistence.sqlite.Migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * V12: 客户幂等键 schema（Chapter-9 §9.4.5/§9.5.4、CONTEXT.md「客户幂等键」、 ADR-0007）。键以 (scope_key,
 * idem_key) 唯一——跨认证主体范围（如项目）互不 可见；digest 为调用方算好的请求摘要（方法+路径+模型+参数）。state 取值：
 * {@code inflight}（在途）/ {@code completed}（结果已知）/ {@code unknown} （结果未知待对账）。在途与未知行不因 TTL
 * 失去防重保护，只有完成态行按 expires_at 过期回收。
 */
public final class IdempotencyMigrations {

	private static final List<String> IDEMPOTENCY_DDL = List.of(
			"CREATE TABLE idempotency_record (scope_key TEXT NOT NULL,"
					+ " idem_key TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL,"
					+ " billing_call_id TEXT, request_id TEXT, token TEXT NOT NULL,"
					+ " created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,"
					+ " PRIMARY KEY(scope_key, idem_key))",
			"CREATE INDEX idx_idempotency_scope_state_expiry" + " ON idempotency_record(scope_key, state, expires_at)");

	private IdempotencyMigrations() {
	}

	/** V12: the customer idempotency-key schema (scope-keyed record table). */
	public static List<Migration> idempotency() {
		return List.of(new Migration() {
			@Override
			public int version() {
				return 12;
			}

			@Override
			public void apply(Connection connection) throws SQLException {
				for (String statement : IDEMPOTENCY_DDL) {
					try (Statement jdbc = connection.createStatement()) {
						jdbc.execute(statement);
					}
				}
			}
		});
	}

}
