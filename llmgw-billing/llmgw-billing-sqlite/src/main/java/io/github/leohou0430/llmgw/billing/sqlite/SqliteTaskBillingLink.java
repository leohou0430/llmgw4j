package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;
import java.util.Optional;

/**
 * 异步任务 ↔ 计费调用关联（任务4）：唯一键 tenant/project/provider-source/ upstream-task-id 指向
 * billingCallId。轮询读取按完整唯一键（跨项目轮询读不 到他人任务）；终态推进 CAS（pending → settled|released 只能发生一次），
 * 重复终态与重启后重复结算都被持久状态机拒绝。
 */
public final class SqliteTaskBillingLink {

	private final DatabaseContext database;

	public SqliteTaskBillingLink(DatabaseContext database) {
		this.database = database;
	}

	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("task billing link transaction failed", e);
		}
	}

	private <T> T inRead(DatabaseContext.SqlWork<T> work) {
		try {
			return database.read(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("task billing link read failed", e);
		}
	}

	/** 任务创建时绑定计费调用（唯一键冲突 = 重复绑定，拒绝）。 */
	public void bind(String tenantId, String projectId, String providerSource, String upstreamTaskId,
			String billingCallId, String model, long priceVersion) {
		inTx(conn -> {
			try (var insert = conn
				.prepareStatement("INSERT INTO task_billing_link (tenant_id, project_id, provider_source,"
						+ " upstream_task_id, billing_call_id, model, price_version, status, created_at)"
						+ " VALUES (?,?,?,?,?,?,?,'pending',?)")) {
				insert.setString(1, tenantId);
				insert.setString(2, projectId);
				insert.setString(3, providerSource);
				insert.setString(4, upstreamTaskId);
				insert.setString(5, billingCallId);
				insert.setString(6, model);
				insert.setLong(7, priceVersion);
				insert.setLong(8, System.currentTimeMillis());
				insert.executeUpdate();
			}
			catch (SQLException duplicate) {
				throw new IllegalStateException("task already linked: " + tenantId + "/" + projectId + "/"
						+ providerSource + "/" + upstreamTaskId);
			}
			return null;
		});
	}

	/** 轮询时恢复计费上下文；键含 project（跨项目轮询读不到他人任务）。 */
	public Optional<Link> find(String tenantId, String projectId, String providerSource, String upstreamTaskId) {
		return inRead(conn -> {
			try (var query = conn.prepareStatement("SELECT billing_call_id, model, price_version, status"
					+ " FROM task_billing_link WHERE tenant_id=? AND project_id=?"
					+ " AND provider_source=? AND upstream_task_id=?")) {
				query.setString(1, tenantId);
				query.setString(2, projectId);
				query.setString(3, providerSource);
				query.setString(4, upstreamTaskId);
				try (var rows = query.executeQuery()) {
					if (!rows.next()) {
						return Optional.<Link>empty();
					}
					return Optional
						.of(new Link(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4)));
				}
			}
		});
	}

	/** 终态：结算（pending → settled；CAS 失败 = 重复终态，拒绝）。 */
	public void markSettled(String tenantId, String projectId, String providerSource, String upstreamTaskId,
			long settledNano) {
		transition(tenantId, projectId, providerSource, upstreamTaskId, "settled", settledNano);
	}

	/** 终态：释放（pending → released；CAS）。 */
	public void markReleased(String tenantId, String projectId, String providerSource, String upstreamTaskId) {
		transition(tenantId, projectId, providerSource, upstreamTaskId, "released", 0);
	}

	private void transition(String tenantId, String projectId, String providerSource, String upstreamTaskId,
			String target, long settledNano) {
		inTx(conn -> {
			try (var update = conn
				.prepareStatement("UPDATE task_billing_link SET status=?, settled_at=?, settled_nano=?"
						+ " WHERE tenant_id=? AND project_id=? AND provider_source=?"
						+ " AND upstream_task_id=? AND status='pending'")) {
				update.setString(1, target);
				update.setLong(2, System.currentTimeMillis());
				update.setLong(3, settledNano);
				update.setString(4, tenantId);
				update.setString(5, projectId);
				update.setString(6, providerSource);
				update.setString(7, upstreamTaskId);
				if (update.executeUpdate() != 1) {
					throw new IllegalStateException("task billing link not in pending state: " + upstreamTaskId);
				}
			}
			return null;
		});
	}

	/** 关联句柄快照。 */
	public record Link(String billingCallId, String model, long priceVersion, String status) {
	}

}
