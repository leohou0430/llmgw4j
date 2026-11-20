package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 账务归档（任务9）：把终态（settled/released）预留移入归档表，持久高水位与
 * 摘要；账户锚点（billing_account）与幂等索引（billing_recharge 的 UNIQUE(tenant_id,
 * voucher_no)）永不归档——归档前后余额/累计/在途不变，充值 凭证重放在归档后仍被幂等拒绝。活动（reserved）与未知（pendingVerify）记录
 * 不能归档。摘要损坏（归档行被篡改）时恢复核对拒绝。
 */
public final class BillingArchiveService {

	private final DatabaseContext database;

	public BillingArchiveService(DatabaseContext database) {
		this.database = database;
	}

	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("billing archive transaction failed", e);
		}
	}

	private <T> T inRead(DatabaseContext.SqlWork<T> work) {
		try {
			return database.read(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("billing archive read failed", e);
		}
	}

	/** 归档结果：迁移行数、高水位（本次归档的最大 settled_at/created_at）、归档行摘要。 */
	public record ArchiveResult(long archived, long highWaterMark, String digest) {
	}

	/** 持久高水位：恢复核对与增量归档的起点。 */
	public record Watermark(long highWaterMark, String digest, long archivedCount, long updatedAt) {
	}

	/**
	 * 归档截止时间之前、状态为 settled/released 的预留。单事务完成（读→写归档→ 删原行→写高水位），崩溃时整体回滚（不产生半归档）。
	 */
	public ArchiveResult archiveBefore(long thresholdMs) {
		return inTx(conn -> {
			long now = System.currentTimeMillis();
			var moved = conn.prepareStatement("INSERT INTO billing_reservation_archive"
					+ " (id, tenant_id, project_id, model, price_version, amount_nano, status,"
					+ "  created_at, settled_at, settled_nano, reason, operator_id, archived_at)"
					+ " SELECT id, tenant_id, project_id, model, price_version, amount_nano, status,"
					+ "  created_at, settled_at, settled_nano, reason, operator_id, ?" + " FROM billing_reservation"
					+ " WHERE status IN ('settled','released')" + "   AND COALESCE(settled_at, created_at) < ?");
			moved.setLong(1, now);
			moved.setLong(2, thresholdMs);
			int movedRows = moved.executeUpdate();
			if (movedRows == 0) {
				Watermark current = watermark().orElse(null);
				return new ArchiveResult(0, current == null ? thresholdMs : current.highWaterMark(),
						current == null ? digestOf(conn) : current.digest());
			}
			var deleted = conn.prepareStatement("DELETE FROM billing_reservation WHERE id IN"
					+ " (SELECT id FROM billing_reservation_archive WHERE archived_at = ?)");
			deleted.setLong(1, now);
			deleted.executeUpdate();
			long highWater = highWaterMark(conn);
			String digest = digestOf(conn);
			var upsert = conn.prepareStatement("INSERT INTO billing_archive_watermark (id, high_water_mark, digest,"
					+ " archived_count, updated_at) VALUES (1,?,?,"
					+ " (SELECT COUNT(*) FROM billing_reservation_archive),?)"
					+ " ON CONFLICT(id) DO UPDATE SET high_water_mark=excluded.high_water_mark,"
					+ " digest=excluded.digest, archived_count=excluded.archived_count,"
					+ " updated_at=excluded.updated_at");
			upsert.setLong(1, highWater);
			upsert.setString(2, digest);
			upsert.setLong(3, now);
			upsert.executeUpdate();
			return new ArchiveResult(movedRows, highWater, digest);
		});
	}

	/** 归档摘要与高水位核对：摘要与归档行重算不一致 → 恢复必须拒绝（损坏归档）。 */
	public void verifyDigest() {
		Watermark mark = watermark()
			.orElseThrow(() -> new IllegalStateException("no archive watermark: nothing archived yet"));
		String actual = inRead(this::digestOf);
		if (!mark.digest().equals(actual)) {
			throw new IllegalStateException(
					"archive digest mismatch: watermark " + mark.digest() + " vs actual " + actual);
		}
	}

	public Optional<Watermark> watermark() {
		return inRead(conn -> {
			try (var query = conn.prepareStatement("SELECT high_water_mark, digest, archived_count, updated_at"
					+ " FROM billing_archive_watermark WHERE id = 1")) {
				try (var rows = query.executeQuery()) {
					if (!rows.next()) {
						return Optional.<Watermark>empty();
					}
					return Optional
						.of(new Watermark(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
				}
			}
		});
	}

	private static long highWaterMark(java.sql.Connection conn) throws SQLException {
		try (var query = conn.prepareStatement(
				"SELECT COALESCE(MAX(COALESCE(settled_at, created_at)),0)" + " FROM billing_reservation_archive")) {
			try (var rows = query.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	/** 归档行 canonical 摘要：按 id 排序拼接核心列后 SHA-256（hex）。 */
	private String digestOf(java.sql.Connection conn) {
		try (var query = conn.prepareStatement("SELECT id, tenant_id, status, amount_nano, COALESCE(settled_nano,0)"
				+ " FROM billing_reservation_archive ORDER BY id")) {
			StringBuilder canonical = new StringBuilder();
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					canonical.append(rows.getString(1))
						.append('|')
						.append(rows.getString(2))
						.append('|')
						.append(rows.getString(3))
						.append('|')
						.append(rows.getLong(4))
						.append('|')
						.append(rows.getLong(5))
						.append('\n');
				}
			}
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] digest = sha.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (java.sql.SQLException | java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("cannot compute archive digest", e);
		}
	}

}
