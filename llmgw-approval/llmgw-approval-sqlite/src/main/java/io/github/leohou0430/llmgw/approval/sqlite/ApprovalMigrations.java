package io.github.leohou0430.llmgw.approval.sqlite;

import io.github.leohou0430.llmgw.persistence.sqlite.Migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * V13: 审批工作流 schema（Chapter-9 §9.5.4）。状态机列 {@code state} 取值
 * {@code pending | approved | rejected | expired | revoked}；迁移全部以 WHERE state 守卫的持久 CAS
 * 完成，并发/重复回调只成功一次。决策人、时间与 原因记录终态达成信息（撤销复用决策原因列，javadoc 同 {@code ApprovalService}）。
 */
public final class ApprovalMigrations {

	private static final List<String> APPROVAL_DDL = List.of(
			"CREATE TABLE approval_record (id TEXT PRIMARY KEY,"
					+ " subject_ref TEXT NOT NULL, tenant_id TEXT NOT NULL,"
					+ " project_id TEXT NOT NULL, target_kind TEXT NOT NULL,"
					+ " target_name TEXT NOT NULL, tool_version TEXT NOT NULL,"
					+ " params_digest TEXT NOT NULL, risk TEXT NOT NULL,"
					+ " state TEXT NOT NULL, decided_by TEXT, decision_reason TEXT,"
					+ " created_at INTEGER NOT NULL, decided_at INTEGER," + " expires_at INTEGER NOT NULL)",
			"CREATE INDEX idx_approval_tenant_state ON approval_record(tenant_id, state, created_at)");

	private ApprovalMigrations() {
	}

	/** V13: the approval workflow schema (durable CAS state machine). */
	public static List<Migration> approval() {
		return List.of(new Migration() {
			@Override
			public int version() {
				return 13;
			}

			@Override
			public void apply(Connection connection) throws SQLException {
				for (String statement : APPROVAL_DDL) {
					try (Statement jdbc = connection.createStatement()) {
						jdbc.execute(statement);
					}
				}
			}
		});
	}

}
