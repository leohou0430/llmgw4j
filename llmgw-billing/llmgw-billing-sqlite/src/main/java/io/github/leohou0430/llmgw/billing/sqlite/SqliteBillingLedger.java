package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingAccount;
import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.ReversalCommand;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite billing ledger (ADR-0007). Admission reservation, settlement with carry
 * remainders and pending-verification all run inside ONE SQLite transaction on the
 * account row, so concurrent callers cannot overspend the available amount and a crash
 * mid-request leaves the reservation row behind (pending-verify across restarts).
 *
 * <p>
 * Amount model: estimates and settlements are computed in whole nano-CNY; admission
 * rounds the estimate UP to a whole micro-CNY; settlement posts the merged whole-micro
 * amount and keeps the per-tenant carry remainder.
 */
public final class SqliteBillingLedger implements BillingLedger {

	private static final int POST_UNIT = 1000; // 1 micro-CNY = 1000 nano

	private final DatabaseContext database;

	public SqliteBillingLedger(DatabaseContext database) {
		this.database = database;
	}

	@Override
	public Reservation reserve(String tenantId, String projectId, String model, long priceVersion, long estimateNano) {
		// Round the estimate UP to a whole micro (ADR: 预留向上取整).
		long amount = roundUpToMicro(estimateNano);
		String id = "res-" + UUID.randomUUID();
		long now = System.currentTimeMillis();
		Long version = inTx(conn -> {
			ensureAccount(conn, tenantId, now);
			// Atomic admission inside one statement: the reservation row insert
			// and the availability check share the same synchronized transaction.
			long reserved = reservedNanoTx(conn, tenantId);
			if (!admittedTx(conn, tenantId, reserved, amount)) {
				return null;
			}
			if (!projectWithinSublimitTx(conn, projectId, amount)) {
				// 子限额是累计额度：已消费 + 在途预留参与准入；不足拒绝新调用。
				return null;
			}
			try (var insert = conn.prepareStatement("INSERT INTO billing_reservation (id, tenant_id, project_id, model,"
					+ " price_version, amount_nano, status, created_at)" + " VALUES (?,?,?,?,?,?,'reserved',?)")) {
				insert.setString(1, id);
				insert.setString(2, tenantId);
				insert.setString(3, projectId);
				insert.setString(4, model);
				insert.setLong(5, priceVersion);
				insert.setLong(6, amount);
				insert.setLong(7, now);
				insert.executeUpdate();
			}
			return priceVersion;
		});
		if (version == null) {
			return null;
		}
		return new Reservation(id, tenantId, projectId, model, priceVersion, amount);
	}

	@Override
	public SettleResult settle(String reservationId, long actualInputTokens, long actualOutputTokens, Price frozen) {
		return inTx(conn -> {
			var row = readReservation(conn, reservationId);
			if (row == null || !"reserved".equals(row.status())) {
				throw new IllegalStateException("reservation " + reservationId + " is not in reserved state");
			}
			long raw = rawNano(actualInputTokens, actualOutputTokens, frozen);
			// Merge the tenant carry remainder, post the whole-micro amount.
			long carry = readCarry(conn, row.tenantId());
			long merged = raw + carry;
			long posted = merged - (merged % POST_UNIT);
			long newCarry = merged - posted;
			long now = System.currentTimeMillis();
			try (var update = conn.prepareStatement(
					"UPDATE billing_reservation SET status='settled', settled_at=?," + " settled_nano=? WHERE id=?")) {
				update.setLong(1, now);
				update.setLong(2, posted);
				update.setString(3, reservationId);
				update.executeUpdate();
			}
			try (var post = conn.prepareStatement("UPDATE billing_account SET balance_nano = balance_nano - ?,"
					+ " carry_nano = ?, updated_at = ? WHERE tenant_id = ?")) {
				post.setLong(1, posted);
				post.setLong(2, newCarry);
				post.setLong(3, now);
				post.setString(4, row.tenantId());
				post.executeUpdate();
			}
			return new SettleResult(reservationId, posted, newCarry);
		});
	}

	@Override
	public SettleResult settleNano(String reservationId, long actualNano) {
		return inTx(conn -> {
			var row = readReservation(conn, reservationId);
			if (row == null || !"reserved".equals(row.status())) {
				throw new IllegalStateException("reservation " + reservationId + " is not in reserved state");
			}
			// 与 token 结算相同的入账：合并租户尾数，入账 whole micro。
			long carry = readCarry(conn, row.tenantId());
			long merged = Math.max(0, actualNano) + carry;
			long posted = merged - (merged % POST_UNIT);
			long newCarry = merged - posted;
			long now = System.currentTimeMillis();
			try (var update = conn.prepareStatement(
					"UPDATE billing_reservation SET status='settled', settled_at=?," + " settled_nano=? WHERE id=?")) {
				update.setLong(1, now);
				update.setLong(2, posted);
				update.setString(3, reservationId);
				update.executeUpdate();
			}
			try (var post = conn.prepareStatement("UPDATE billing_account SET balance_nano = balance_nano - ?,"
					+ " carry_nano = ?, updated_at = ? WHERE tenant_id = ?")) {
				post.setLong(1, posted);
				post.setLong(2, newCarry);
				post.setLong(3, now);
				post.setString(4, row.tenantId());
				post.executeUpdate();
			}
			return new SettleResult(reservationId, posted, newCarry);
		});
	}

	@Override
	public void markPendingVerify(String reservationId, String reason) {
		inTx(conn -> {
			try (var update = conn.prepareStatement("UPDATE billing_reservation SET status='pendingVerify', reason=?"
					+ " WHERE id=? AND status='reserved'")) {
				update.setString(1, reason);
				update.setString(2, reservationId);
				update.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public void release(String reservationId, String reason, String operatorId) {
		inTx(conn -> {
			try (var update = conn
				.prepareStatement("UPDATE billing_reservation SET status='released', reason=?, operator_id=?"
						+ " WHERE id=? AND status='reserved'")) {
				update.setString(1, reason);
				update.setString(2, operatorId);
				update.setString(3, reservationId);
				update.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public SettleResult settlePending(String reservationId, long actualNano, String reason, String operatorId) {
		return inTx(conn -> {
			var row = readReservation(conn, reservationId);
			if (row == null || !"pendingVerify".equals(row.status())) {
				throw new IllegalStateException("reservation " + reservationId + " is not pendingVerify");
			}
			long carry = readCarry(conn, row.tenantId());
			long merged = Math.max(0, actualNano) + carry;
			long posted = merged - (merged % POST_UNIT);
			long newCarry = merged - posted;
			long now = System.currentTimeMillis();
			try (var update = conn.prepareStatement("UPDATE billing_reservation SET status='settled', settled_at=?,"
					+ " settled_nano=?, reason=?, operator_id=? WHERE id=?")) {
				update.setLong(1, now);
				update.setLong(2, posted);
				update.setString(3, reason);
				update.setString(4, operatorId);
				update.setString(5, reservationId);
				update.executeUpdate();
			}
			try (var post = conn.prepareStatement("UPDATE billing_account SET balance_nano = balance_nano - ?,"
					+ " carry_nano = ?, updated_at = ? WHERE tenant_id = ?")) {
				post.setLong(1, posted);
				post.setLong(2, newCarry);
				post.setLong(3, now);
				post.setString(4, row.tenantId());
				post.executeUpdate();
			}
			return new SettleResult(reservationId, posted, newCarry);
		});
	}

	@Override
	public void releasePending(String reservationId, String reason, String operatorId) {
		inTx(conn -> {
			try (var update = conn
				.prepareStatement("UPDATE billing_reservation SET status='released', reason=?, operator_id=?"
						+ " WHERE id=? AND status='pendingVerify'")) {
				update.setString(1, reason);
				update.setString(2, operatorId);
				update.setString(3, reservationId);
				update.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public List<Reservation> pendingVerifications(String tenantId) {
		return inRead(conn -> {
			var list = new ArrayList<Reservation>();
			try (var query = conn.prepareStatement("SELECT id, tenant_id, project_id, model, price_version, amount_nano"
					+ " FROM billing_reservation WHERE tenant_id=? AND status='pendingVerify'"
					+ " ORDER BY created_at")) {
				query.setString(1, tenantId);
				try (var rows = query.executeQuery()) {
					while (rows.next()) {
						list.add(new Reservation(rows.getString(1), rows.getString(2), rows.getString(3),
								rows.getString(4), rows.getLong(5), rows.getLong(6)));
					}
				}
			}
			return List.copyOf(list);
		});
	}

	@Override
	public RechargeResult recharge(String tenantId, long amountNano, String voucherNo, String operatorId) {
		if (amountNano == 0) {
			throw new IllegalArgumentException("recharge amount must be non-zero");
		}
		String id = "rcg-" + UUID.randomUUID();
		long now = System.currentTimeMillis();
		return inTx(conn -> {
			ensureAccount(conn, tenantId, now);
			// Voucher idempotency: UNIQUE(tenant_id, voucher_no) — a duplicate
			// submit returns the original entry without double-crediting.
			try (var insert = conn
				.prepareStatement("INSERT INTO billing_recharge (id, tenant_id, amount_nano, voucher_no,"
						+ " operator_id, kind, created_at) VALUES (?,?,?,?,?,'recharge',?)")) {
				insert.setString(1, id);
				insert.setString(2, tenantId);
				insert.setLong(3, amountNano);
				insert.setString(4, voucherNo);
				insert.setString(5, operatorId);
				insert.setLong(6, now);
				insert.executeUpdate();
			}
			catch (SQLException duplicate) {
				var existing = findByVoucher(conn, tenantId, voucherNo);
				return new RechargeResult(existing.entryId(), tenantId, existing.amountNano(), voucherNo, true);
			}
			try (var credit = conn
				.prepareStatement("UPDATE billing_account SET balance_nano = balance_nano + ?, updated_at = ?"
						+ " WHERE tenant_id = ?")) {
				credit.setLong(1, amountNano);
				credit.setLong(2, now);
				credit.setString(3, tenantId);
				credit.executeUpdate();
			}
			return new RechargeResult(id, tenantId, amountNano, voucherNo, false);
		});
	}

	/**
	 * 冲正（第十一项确认）：整笔负入账引用原充值流水；原流水必须存在、属于本 租户且尚未被冲正；同 operationId 重放幂等返回首次结果。
	 */
	@Override
	public RechargeResult reverse(ReversalCommand command) {
		long now = System.currentTimeMillis();
		return inTx(conn -> {
			ensureAccount(conn, command.tenantId(), now);
			// 引用的原流水必须存在且为正充值（冲正行不能再被冲正）。
			long originalAmount;
			try (var query = conn.prepareStatement("SELECT amount_nano FROM billing_recharge WHERE id=? AND tenant_id=?"
					+ " AND kind='recharge' AND amount_nano > 0")) {
				query.setString(1, command.originalEntryId());
				query.setString(2, command.tenantId());
				try (var rows = query.executeQuery()) {
					if (!rows.next()) {
						throw new IllegalArgumentException(
								"reversal target is not a recharge entry of this tenant: " + command.originalEntryId());
					}
					originalAmount = rows.getLong(1);
				}
			}
			// 已被冲正：同 operationId 重放 = 幂等命中；不同 operationId = 拒绝。
			String previous;
			try (var dup = conn.prepareStatement(
					"SELECT voucher_no FROM billing_recharge WHERE kind='reversal'" + " AND related_id=?")) {
				dup.setString(1, command.originalEntryId());
				try (var rows = dup.executeQuery()) {
					previous = rows.next() ? rows.getString(1) : null;
				}
			}
			if (previous != null) {
				if (previous.equals(command.operationId())) {
					return findByVoucher(conn, command.tenantId(), command.operationId());
				}
				throw new IllegalStateException("entry already reversed: " + command.originalEntryId());
			}
			String id = "rcg-" + UUID.randomUUID();
			try (var insert = conn
				.prepareStatement("INSERT INTO billing_recharge (id, tenant_id, amount_nano, voucher_no,"
						+ " operator_id, kind, related_id, created_at) VALUES (?,?,?,?,?,'reversal',?,?)")) {
				insert.setString(1, id);
				insert.setString(2, command.tenantId());
				insert.setLong(3, -originalAmount);
				insert.setString(4, command.operationId());
				insert.setString(5, command.operatorId());
				insert.setString(6, command.originalEntryId());
				insert.setLong(7, now);
				insert.executeUpdate();
			}
			catch (SQLException duplicate) {
				// UNIQUE(tenant_id, voucher_no) 命中：同 operationId 的并发重放。
				return findByVoucher(conn, command.tenantId(), command.operationId());
			}
			try (var credit = conn
				.prepareStatement("UPDATE billing_account SET balance_nano = balance_nano + ?, updated_at = ?"
						+ " WHERE tenant_id = ?")) {
				credit.setLong(1, -originalAmount);
				credit.setLong(2, now);
				credit.setString(3, command.tenantId());
				credit.executeUpdate();
			}
			return new RechargeResult(id, command.tenantId(), -originalAmount, command.operationId(), false);
		});
	}

	@Override
	public Optional<BillingAccount> account(String tenantId) {
		return inRead(conn -> {
			ensureAccount(conn, tenantId, System.currentTimeMillis());
			try (var query = conn
				.prepareStatement("SELECT tenant_id, currency, balance_nano, overdraft_nano, carry_nano"
						+ " FROM billing_account WHERE tenant_id = ?")) {
				query.setString(1, tenantId);
				try (var rows = query.executeQuery()) {
					if (!rows.next()) {
						return Optional.<BillingAccount>empty();
					}
					return Optional.of(new BillingAccount(rows.getString(1), rows.getString(2), rows.getLong(3),
							rows.getLong(4), rows.getLong(5)));
				}
			}
		});
	}

	@Override
	public long reservedNano(String tenantId) {
		return inRead(conn -> {
			try (var query = conn.prepareStatement("SELECT COALESCE(SUM(amount_nano),0) FROM billing_reservation"
					+ " WHERE tenant_id=? AND status IN ('reserved','pendingVerify')")) {
				query.setString(1, tenantId);
				try (var rows = query.executeQuery()) {
					return rows.next() ? rows.getLong(1) : 0L;
				}
			}
		});
	}

	@Override
	public void setOverdraft(String tenantId, long overdraftNano) {
		inTx(conn -> {
			ensureAccount(conn, tenantId, System.currentTimeMillis());
			try (var update = conn.prepareStatement(
					"UPDATE billing_account SET overdraft_nano = ?, updated_at = ?" + " WHERE tenant_id = ?")) {
				update.setLong(1, Math.max(0, overdraftNano));
				update.setLong(2, System.currentTimeMillis());
				update.setString(3, tenantId);
				update.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public void setProjectSublimit(String tenantId, String projectId, long limitNano) {
		inTx(conn -> {
			try (var upsert = conn.prepareStatement("INSERT INTO project_sublimit (project_id, tenant_id, limit_nano)"
					+ " VALUES (?,?,?) ON CONFLICT(project_id)" + " DO UPDATE SET limit_nano = excluded.limit_nano,"
					+ " tenant_id = excluded.tenant_id")) {
				upsert.setString(1, projectId);
				upsert.setString(2, tenantId);
				upsert.setLong(3, Math.max(0, limitNano));
				upsert.executeUpdate();
			}
			return null;
		});
	}

	@Override
	public Optional<Long> projectSublimit(String projectId) {
		return inRead(conn -> {
			try (var query = conn.prepareStatement("SELECT limit_nano FROM project_sublimit WHERE project_id = ?")) {
				query.setString(1, projectId);
				try (var rows = query.executeQuery()) {
					return rows.next() ? Optional.of(rows.getLong(1)) : Optional.<Long>empty();
				}
			}
		});
	}

	// ------------------------------------------------------------ internals

	/**
	 * BillingLedger SPI 方法不抛 SQL 受检异常：SQLite 的受检异常统一包装为 IllegalStateException（与数据面
	 * provider 的运行时异常契约一致）。
	 */
	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("billing transaction failed", e);
		}
	}

	private <T> T inRead(DatabaseContext.SqlWork<T> work) {
		try {
			return database.read(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("billing read failed", e);
		}
	}

	private record ReservationRow(String id, String tenantId, long amountNano, String status) {
	}

	private ReservationRow readReservation(java.sql.Connection conn, String reservationId) {
		try (var query = conn
			.prepareStatement("SELECT id, tenant_id, amount_nano, status FROM billing_reservation WHERE id = ?")) {
			query.setString(1, reservationId);
			try (var rows = query.executeQuery()) {
				if (!rows.next()) {
					return null;
				}
				return new ReservationRow(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4));
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private long readCarry(java.sql.Connection conn, String tenantId) {
		try (var query = conn.prepareStatement("SELECT carry_nano FROM billing_account WHERE tenant_id = ?")) {
			query.setString(1, tenantId);
			try (var rows = query.executeQuery()) {
				return rows.next() ? rows.getLong(1) : 0L;
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private RechargeResult findByVoucher(java.sql.Connection conn, String tenantId, String voucherNo) {
		try (var query = conn
			.prepareStatement("SELECT id, amount_nano FROM billing_recharge WHERE tenant_id=? AND voucher_no=?")) {
			query.setString(1, tenantId);
			query.setString(2, voucherNo);
			try (var rows = query.executeQuery()) {
				if (rows.next()) {
					return new RechargeResult(rows.getString(1), tenantId, rows.getLong(2), voucherNo, true);
				}
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		throw new IllegalStateException("voucher row vanished: " + voucherNo);
	}

	private void ensureAccount(java.sql.Connection conn, String tenantId, long now) throws SQLException {
		try (var insert = conn.prepareStatement("INSERT INTO billing_account (tenant_id, currency, balance_nano,"
				+ " overdraft_nano, carry_nano, updated_at) VALUES (?,'CNY',0,0,0,?)"
				+ " ON CONFLICT(tenant_id) DO NOTHING")) {
			insert.setString(1, tenantId);
			insert.setLong(2, now);
			insert.executeUpdate();
		}
	}

	private long reservedNanoTx(java.sql.Connection conn, String tenantId) throws SQLException {
		try (var query = conn.prepareStatement("SELECT COALESCE(SUM(amount_nano),0) FROM billing_reservation"
				+ " WHERE tenant_id=? AND status IN ('reserved','pendingVerify')")) {
			query.setString(1, tenantId);
			try (var rows = query.executeQuery()) {
				return rows.next() ? rows.getLong(1) : 0L;
			}
		}
	}

	/**
	 * 准入判定：余额 + 透支 − 在途预留 ≥ 本次预留（同一事务内读数与判定）。 项目子限额：已消费 + 在途预留 + 本次 ≥ 子限额时拒绝；子限额以
	 * reservation 行的 settled_nano/amount_nano 聚合近似（结算后按实际金额）。
	 */
	private boolean admittedTx(java.sql.Connection conn, String tenantId, long reservedTotal, long amount)
			throws SQLException {
		try (var query = conn
			.prepareStatement("SELECT balance_nano + overdraft_nano FROM billing_account WHERE tenant_id = ?")) {
			query.setString(1, tenantId);
			try (var rows = query.executeQuery()) {
				if (!rows.next()) {
					return false;
				}
				return rows.getLong(1) - reservedTotal >= amount;
			}
		}
	}

	/**
	 * B4 子限额准入：已消费（settled 实际金额）+ 在途预留 + 本次 ≤ 子限额。 子限额未配置（无行）时不限制。
	 */
	private boolean projectWithinSublimitTx(java.sql.Connection conn, String projectId, long amount)
			throws SQLException {
		try (var query = conn.prepareStatement("SELECT limit_nano FROM project_sublimit WHERE project_id = ?")) {
			query.setString(1, projectId);
			try (var rows = query.executeQuery()) {
				if (!rows.next()) {
					return true;
				}
				long limit = rows.getLong(1);
			}
		}
		try (var query = conn
			.prepareStatement("SELECT COALESCE(SUM(CASE WHEN status = 'settled' THEN COALESCE(settled_nano,0)"
					+ " WHEN status IN ('reserved','pendingVerify') THEN amount_nano ELSE 0 END),0)"
					+ " FROM billing_reservation WHERE project_id = ?"
					+ " AND status IN ('settled','reserved','pendingVerify')")) {
			query.setString(1, projectId);
			try (var rows = query.executeQuery()) {
				long used = rows.next() ? rows.getLong(1) : 0L;
				try (var limitQuery = conn
					.prepareStatement("SELECT limit_nano FROM project_sublimit WHERE project_id = ?")) {
					limitQuery.setString(1, projectId);
					try (var limitRows = limitQuery.executeQuery()) {
						if (!limitRows.next()) {
							return true;
						}
						return used + amount <= limitRows.getLong(1);
					}
				}
			}
		}
	}

	private static long roundUpToMicro(long nano) {
		if (nano <= 0) {
			return POST_UNIT;
		}
		return ((nano + POST_UNIT - 1) / POST_UNIT) * POST_UNIT;
	}

	private static long rawNano(long inputTokens, long outputTokens, Price frozen) {
		long in = Math.max(0, inputTokens) * Math.max(0, frozen.inputNanoPerMillion());
		long out = Math.max(0, outputTokens) * Math.max(0, frozen.outputNanoPerMillion());
		return (in + out + 500_000) / 1_000_000; // nano-CNY, rounded half-up
	}

}
