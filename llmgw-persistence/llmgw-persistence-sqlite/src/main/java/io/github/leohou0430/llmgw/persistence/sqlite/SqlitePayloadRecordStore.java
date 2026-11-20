package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import io.github.leohou0430.llmgw.persistence.PayloadRecordStore;
import io.github.leohou0430.llmgw.persistence.PayloadRecordStore.PayloadRecord;
import io.github.leohou0430.llmgw.persistence.PayloadStoreUnavailableException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 调用正文记录存储（V11）。写入失败（磁盘满 SQLITE_FULL/IO 错误）映射为
 * {@link PayloadStoreUnavailableException}；清理按 expires_at 索引批量删除。
 */
public final class SqlitePayloadRecordStore implements PayloadRecordStore {

	private final DatabaseContext database;

	public SqlitePayloadRecordStore(DatabaseContext database) {
		this.database = database;
	}

	@Override
	public void put(String id, String tenantId, String projectId, String requestBody, String responseBody,
			String structured, long createdAtEpochMs, long expiresAtEpochMs, boolean truncated) {
		try {
			database.transaction(conn -> {
				try (var insert = conn
					.prepareStatement("INSERT INTO payload_record (id, tenant_id, project_id, request_body,"
							+ " response_body, structured, created_at, expires_at, truncated)"
							+ " VALUES (?,?,?,?,?,?,?,?,?)")) {
					insert.setString(1, id);
					insert.setString(2, tenantId);
					insert.setString(3, projectId);
					insert.setString(4, requestBody);
					insert.setString(5, responseBody);
					insert.setString(6, structured);
					insert.setLong(7, createdAtEpochMs);
					insert.setLong(8, expiresAtEpochMs);
					insert.setInt(9, truncated ? 1 : 0);
					insert.executeUpdate();
				}
				return null;
			});
		}
		catch (SQLException e) {
			throw new PayloadStoreUnavailableException("payload record write failed (storage full or io error)", e);
		}
	}

	@Override
	public Optional<PayloadRecord> get(String id) {
		try {
			return database.read(conn -> {
				try (var query = conn.prepareStatement("SELECT id, tenant_id, project_id, request_body, response_body,"
						+ " structured, created_at, expires_at, truncated" + " FROM payload_record WHERE id = ?")) {
					query.setString(1, id);
					try (var rows = query.executeQuery()) {
						if (!rows.next()) {
							return Optional.<PayloadRecord>empty();
						}
						return Optional.of(new PayloadRecord(rows.getString(1), rows.getString(2), rows.getString(3),
								rows.getString(4), rows.getString(5), rows.getString(6), rows.getLong(7),
								rows.getLong(8), rows.getInt(9) != 0));
					}
				}
			});
		}
		catch (SQLException e) {
			throw new PayloadStoreUnavailableException("payload read failed", e);
		}
	}

	@Override
	public int purgeExpired(long nowEpochMs) {
		try {
			return database.transaction(conn -> {
				try (var delete = conn.prepareStatement("DELETE FROM payload_record WHERE expires_at <= ?")) {
					delete.setLong(1, nowEpochMs);
					return delete.executeUpdate();
				}
			});
		}
		catch (SQLException e) {
			// 清理失败不抛不可用：清理是后台动作，下次到期窗口重试。
			return 0;
		}
	}

	@Override
	public List<PayloadRecord> listRecent(int limit) {
		try {
			return database.read(conn -> {
				var out = new ArrayList<PayloadRecord>();
				try (var query = conn.prepareStatement("SELECT id, tenant_id, project_id, request_body, response_body,"
						+ " structured, created_at, expires_at, truncated"
						+ " FROM payload_record ORDER BY created_at DESC LIMIT ?")) {
					query.setInt(1, Math.max(1, limit));
					try (var rows = query.executeQuery()) {
						while (rows.next()) {
							out.add(new PayloadRecord(rows.getString(1), rows.getString(2), rows.getString(3),
									rows.getString(4), rows.getString(5), rows.getString(6), rows.getLong(7),
									rows.getLong(8), rows.getInt(9) != 0));
						}
					}
				}
				return List.copyOf(out);
			});
		}
		catch (SQLException e) {
			throw new PayloadStoreUnavailableException("payload export failed", e);
		}
	}

	@Override
	public long count() {
		try {
			return database.read(conn -> {
				try (var query = conn.prepareStatement("SELECT COUNT(*) FROM payload_record")) {
					try (var rows = query.executeQuery()) {
						return rows.next() ? rows.getLong(1) : 0L;
					}
				}
			});
		}
		catch (SQLException e) {
			return -1;
		}
	}

}
