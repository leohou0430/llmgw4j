package io.github.leohou0430.llmgw.idempotency.sqlite;

import io.github.leohou0430.llmgw.idempotency.IdempotencyStore;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;
import java.util.UUID;

/**
 * SQLite 客户幂等键存储（Chapter-9 §9.4.5/§9.5.4、ADR-0007）。begin 的 「惰性清理同 scope 过期完成行 → 读行判读 →
 * INSERT 占位」在同一个 SQLite 事务内完成，并发同键只有一个 FRESH，其余请求读到既有行（EXISTING_* /
 * MISMATCH），不会产生重复提交。终态推进由<b>推进令牌</b>守卫：begin（FRESH） 签发一次性
 * {@code beginToken}，只有持有该令牌的原请求可以 complete——TTL 后同键同摘要的新占位持有新令牌，迟到的旧回调一律 no-op（摘要相等也无法
 * 区分新旧调用，令牌是不可伪造的所有权凭据）。
 *
 * <p>
 * state 编码：{@code inflight} 在途、{@code completed} 结果已知、 {@code unknown} 结果未知（待对账）。在途与未知行不因
 * TTL 失去防重保护； 只有完成态行按 expires_at 过期，过期后同键可再次作为新调用占位。
 * </p>
 */
public final class SqliteIdempotencyStore implements IdempotencyStore {

	private static final String STATE_INFLIGHT = "inflight";

	private static final String STATE_COMPLETED = "completed";

	private static final String STATE_UNKNOWN = "unknown";

	private final DatabaseContext database;

	public SqliteIdempotencyStore(DatabaseContext database) {
		this.database = database;
	}

	@Override
	public BeginResult begin(String scopeKey, String idempotencyKey, String requestDigest, long ttlMs,
			long nowEpochMs) {
		requireText(scopeKey, "scopeKey");
		requireText(idempotencyKey, "idempotencyKey");
		requireText(requestDigest, "requestDigest");
		if (ttlMs <= 0) {
			throw new IllegalArgumentException("ttlMs must be positive");
		}
		long expiresAt = nowEpochMs + ttlMs;
		return inTx(conn -> {
			// 惰性清理：同 scope 已过 TTL 的完成态行视同无行（键可再次作为新调用）。
			try (var purge = conn.prepareStatement("DELETE FROM idempotency_record WHERE scope_key=? AND state='"
					+ STATE_COMPLETED + "' AND expires_at<=?")) {
				purge.setString(1, scopeKey);
				purge.setLong(2, nowEpochMs);
				purge.executeUpdate();
			}
			var row = readRow(conn, scopeKey, idempotencyKey);
			if (row == null) {
				String token = "idt-" + UUID.randomUUID();
				insertInflight(conn, scopeKey, idempotencyKey, requestDigest, token, nowEpochMs, expiresAt);
				return new BeginResult(Status.FRESH, null, null, expiresAt, token);
			}
			// 摘要守卫：同键异摘要一律 MISMATCH（调用方 409 拒绝）。
			if (!row.digest().equals(requestDigest)) {
				return BeginResult.observed(Status.MISMATCH, row.billingCallId(), row.requestId(), row.expiresAt());
			}
			return switch (row.state()) {
				case STATE_INFLIGHT -> BeginResult.observed(Status.EXISTING_INFLIGHT, row.billingCallId(),
						row.requestId(), row.expiresAt());
				case STATE_COMPLETED -> BeginResult.observed(Status.EXISTING_COMPLETED, row.billingCallId(),
						row.requestId(), row.expiresAt());
				case STATE_UNKNOWN -> BeginResult.observed(Status.EXISTING_UNKNOWN, row.billingCallId(),
						row.requestId(), row.expiresAt());
				default -> throw new IllegalStateException("unknown idempotency state: " + row.state());
			};
		});
	}

	@Override
	public void complete(String scopeKey, String idempotencyKey, String requestDigest, String beginToken,
			String billingCallId, String requestId, boolean outcomeKnown, long nowEpochMs) {
		requireText(scopeKey, "scopeKey");
		requireText(idempotencyKey, "idempotencyKey");
		requireText(requestDigest, "requestDigest");
		// 无令牌的推进必是观察者或迟到回调：一律 no-op（终态推进是所有权操作）。
		if (beginToken == null || beginToken.isBlank()) {
			return;
		}
		inTx(conn -> {
			// 令牌+摘要双守卫：TTL 后被新调用替换的占位持有新令牌，旧回调
			// （旧令牌）不匹配即忽略，不能结算新调用。
			String terminal = outcomeKnown ? STATE_COMPLETED : STATE_UNKNOWN;
			try (var update = conn.prepareStatement("UPDATE idempotency_record SET state=?, billing_call_id=?,"
					+ " request_id=? WHERE scope_key=? AND idem_key=? AND state='" + STATE_INFLIGHT
					+ "' AND digest=? AND token=?")) {
				update.setString(1, terminal);
				update.setString(2, billingCallId);
				update.setString(3, requestId);
				update.setString(4, scopeKey);
				update.setString(5, idempotencyKey);
				update.setString(6, requestDigest);
				update.setString(7, beginToken);
				update.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public int purgeExpired(long nowEpochMs) {
		return inTx(conn -> {
			try (var purge = conn.prepareStatement(
					"DELETE FROM idempotency_record WHERE state='" + STATE_COMPLETED + "' AND expires_at<=?")) {
				purge.setLong(1, nowEpochMs);
				return purge.executeUpdate();
			}
		});
	}

	// ------------------------------------------------------------ internals

	/**
	 * IdempotencyStore SPI 方法不抛 SQL 受检异常：SQLite 的受检异常统一包装为 IllegalStateException（与
	 * billing 数据面实现契约一致）。
	 */
	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("idempotency transaction failed", e);
		}
	}

	private record Row(String digest, String state, String billingCallId, String requestId, long expiresAt) {
	}

	private Row readRow(java.sql.Connection conn, String scopeKey, String idemKey) {
		try (var query = conn.prepareStatement("SELECT digest, state, billing_call_id, request_id, expires_at"
				+ " FROM idempotency_record WHERE scope_key=? AND idem_key=?")) {
			query.setString(1, scopeKey);
			query.setString(2, idemKey);
			try (var rows = query.executeQuery()) {
				if (!rows.next()) {
					return null;
				}
				return new Row(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
						rows.getLong(5));
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private void insertInflight(java.sql.Connection conn, String scopeKey, String idemKey, String digest, String token,
			long createdAt, long expiresAt) throws SQLException {
		// 九列六占位：state 与两个 NULL 字面内联；占位符 1..3 为键与摘要，
		// 4 为推进令牌，5/6 为 created_at/expires_at。
		try (var insert = conn.prepareStatement("INSERT INTO idempotency_record (scope_key, idem_key, digest, state,"
				+ " billing_call_id, request_id, token, created_at, expires_at)"
				+ " VALUES (?,?,?,'inflight',NULL,NULL,?,?,?)")) {
			insert.setString(1, scopeKey);
			insert.setString(2, idemKey);
			insert.setString(3, digest);
			insert.setString(4, token);
			insert.setLong(5, createdAt);
			insert.setLong(6, expiresAt);
			insert.executeUpdate();
		}
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
