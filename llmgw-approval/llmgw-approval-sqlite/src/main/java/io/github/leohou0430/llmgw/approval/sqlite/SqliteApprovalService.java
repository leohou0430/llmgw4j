package io.github.leohou0430.llmgw.approval.sqlite;

import io.github.leohou0430.llmgw.approval.ApprovalService;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite 审批存储（Chapter-9 §9.5.4）。全部状态迁移以 WHERE state 守卫的持久 CAS
 * 完成：并发或重复回调只成功一次（「重复回调和调用重试不能产生重复副 作用」）。决策人与原因记录终态达成信息；撤销复用决策原因列（与
 * {@code ApprovalService.Approval} javadoc 一致）。过期在决策/重校验时观测： 审批超时不自动降级为只读，也不自动重开审批。
 */
public final class SqliteApprovalService implements ApprovalService {

	private final DatabaseContext database;

	public SqliteApprovalService(DatabaseContext database) {
		this.database = database;
	}

	@Override
	public Approval submit(String subjectRef, String tenantId, String projectId, String targetKind, String targetName,
			String toolVersion, String paramsDigest, String risk, long ttlMs, long nowEpochMs) {
		requireText(subjectRef, "subjectRef");
		requireText(tenantId, "tenantId");
		requireText(projectId, "projectId");
		requireText(targetKind, "targetKind");
		requireText(targetName, "targetName");
		requireText(toolVersion, "toolVersion");
		requireText(paramsDigest, "paramsDigest");
		requireText(risk, "risk");
		if (ttlMs <= 0) {
			throw new IllegalArgumentException("ttlMs must be positive");
		}
		String id = "apr-" + UUID.randomUUID();
		long expiresAt = nowEpochMs + ttlMs;
		return inTx(conn -> {
			try (var insert = conn
				.prepareStatement("INSERT INTO approval_record (id, subject_ref, tenant_id, project_id,"
						+ " target_kind, target_name, tool_version, params_digest, risk, state,"
						+ " decided_by, decision_reason, created_at, decided_at, expires_at)"
						+ " VALUES (?,?,?,?,?,?,?,?,?,'pending',NULL,NULL,?,NULL,?)")) {
				insert.setString(1, id);
				insert.setString(2, subjectRef);
				insert.setString(3, tenantId);
				insert.setString(4, projectId);
				insert.setString(5, targetKind);
				insert.setString(6, targetName);
				insert.setString(7, toolVersion);
				insert.setString(8, paramsDigest);
				insert.setString(9, risk);
				insert.setLong(10, nowEpochMs);
				insert.setLong(11, expiresAt);
				insert.executeUpdate();
			}
			return read(conn, id).orElseThrow();
		});
	}

	@Override
	public Optional<Approval> get(String id) {
		requireText(id, "id");
		return inTx(conn -> read(conn, id));
	}

	@Override
	public List<Approval> pending(String tenantId) {
		requireText(tenantId, "tenantId");
		return inTx(conn -> {
			List<Approval> out = new ArrayList<>();
			try (var query = conn.prepareStatement("SELECT " + COLUMNS + " FROM approval_record"
					+ " WHERE tenant_id=? AND state='pending' ORDER BY created_at ASC, id ASC")) {
				query.setString(1, tenantId);
				try (var rows = query.executeQuery()) {
					while (rows.next()) {
						out.add(mapRow(rows));
					}
				}
			}
			return List.copyOf(out);
		});
	}

	@Override
	public Approval decide(String id, boolean approve, String decidedBy, String reason, long nowEpochMs) {
		requireText(id, "id");
		requireText(decidedBy, "decidedBy");
		requireText(reason, "reason");
		return inTx(conn -> {
			Approval current = read(conn, id).orElseThrow(() -> new NoSuchElementException("unknown approval: " + id));
			// 幂等：终态重复决策返回原快照，不翻转结论。
			if (current.state() != State.PENDING) {
				return current;
			}
			// 过期观测：PENDING 但已过有效期先转 EXPIRED，再拒绝本次决策。
			if (nowEpochMs > current.expiresAtEpochMs()) {
				try (var expire = conn.prepareStatement("UPDATE approval_record SET state='expired', decided_at=?"
						+ " WHERE id=? AND state='pending'")) {
					expire.setLong(1, nowEpochMs);
					expire.setString(2, id);
					expire.executeUpdate();
				}
				return read(conn, id).orElseThrow();
			}
			String target = approve ? "approved" : "rejected";
			try (var update = conn.prepareStatement("UPDATE approval_record SET state=?, decided_by=?, decided_at=?,"
					+ " decision_reason=? WHERE id=? AND state='pending'")) {
				update.setString(1, target);
				update.setString(2, decidedBy);
				update.setLong(3, nowEpochMs);
				update.setString(4, reason);
				update.setString(5, id);
				update.executeUpdate();
			}
			return read(conn, id).orElseThrow();
		});
	}

	@Override
	public boolean revalidate(String id, String subjectRef, String paramsDigest, long nowEpochMs) {
		requireText(id, "id");
		return inTx(conn -> read(conn, id)
			.map(approval -> approval.state() == State.APPROVED && approval.subjectRef().equals(safe(subjectRef))
					&& approval.paramsDigest().equals(safe(paramsDigest)) && nowEpochMs <= approval.expiresAtEpochMs())
			.orElse(false));
	}

	@Override
	public Approval revoke(String id, String revokedBy, String reason, long nowEpochMs) {
		requireText(id, "id");
		requireText(revokedBy, "revokedBy");
		requireText(reason, "reason");
		return inTx(conn -> {
			Approval current = read(conn, id).orElseThrow(() -> new NoSuchElementException("unknown approval: " + id));
			// 仅 APPROVED 可撤销；其余状态为 no-op 返回原快照；已 REVOKED 幂等返回。
			if (current.state() != State.APPROVED) {
				return current;
			}
			try (var update = conn
				.prepareStatement("UPDATE approval_record SET state='revoked', decided_by=?, decided_at=?,"
						+ " decision_reason=? WHERE id=? AND state='approved'")) {
				update.setString(1, revokedBy);
				update.setLong(2, nowEpochMs);
				update.setString(3, reason);
				update.setString(4, id);
				update.executeUpdate();
			}
			return read(conn, id).orElseThrow();
		});
	}

	// ------------------------------------------------------------ internals

	private static final String COLUMNS = "id, subject_ref, tenant_id, project_id, target_kind, target_name,"
			+ " tool_version, params_digest, risk, state, decided_by, decision_reason,"
			+ " created_at, decided_at, expires_at";

	/**
	 * SPI 方法不抛 SQL 受检异常：受检异常统一包装为 IllegalStateException （与 billing/idempotency
	 * 数据面实现契约一致）。
	 */
	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("approval transaction failed", e);
		}
	}

	private Optional<Approval> read(java.sql.Connection conn, String id) throws SQLException {
		try (var query = conn.prepareStatement("SELECT " + COLUMNS + " FROM approval_record WHERE id=?")) {
			query.setString(1, id);
			try (var rows = query.executeQuery()) {
				return rows.next() ? Optional.of(mapRow(rows)) : Optional.empty();
			}
		}
	}

	private Approval mapRow(java.sql.ResultSet rows) throws SQLException {
		// 列序：1 id, 2 subject_ref, 3 tenant_id, 4 project_id, 5 target_kind,
		// 6 target_name, 7 tool_version, 8 params_digest, 9 risk, 10 state,
		// 11 decided_by, 12 decision_reason, 13 created_at, 14 decided_at, 15 expires_at。
		return new Approval(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
				rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9),
				rows.getLong(13), rows.getLong(15), state(rows.getString(10)), rows.getString(11), rows.getLong(14),
				rows.getString(12));
	}

	private static ApprovalService.State state(String value) {
		return switch (value) {
			case "pending" -> State.PENDING;
			case "approved" -> State.APPROVED;
			case "rejected" -> State.REJECTED;
			case "expired" -> State.EXPIRED;
			case "revoked" -> State.REVOKED;
			default -> throw new IllegalStateException("unknown approval state: " + value);
		};
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
