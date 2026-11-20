package io.github.leohou0430.llmgw.billing;

import java.util.List;
import java.util.Optional;

/**
 * 租户预付费账本（ADR-0007）。所有金额为 nano-CNY 整数；入账粒度 1 micro-CNY （1000 nano），尾数按租户 {@code carry}
 * 累计不丢弃。预留状态机： reserved → settled | pendingVerify | released。
 *
 * <p>
 * 并发合同：同一租户的准入在单一事务内完成（读余额/在途 → 判定 → 写预留）， 并发调用不能超出「计费可用金额 = 余额 + 透支 − 在途预留」。结果未知转
 * pendingVerify 跨重启保留，核实后按命令结算或释放。
 */
public interface BillingLedger {

	/**
	 * 原子准入预留：估算向上取整到 whole micro 并写入在途。计费可用金额不足 时返回 null（拒绝是正常路径，不抛异常）。价格版本号在准入时冻结。
	 */
	Reservation reserve(String tenantId, String projectId, String model, long priceVersion, long estimateNano);

	/**
	 * 按实际 token 用冻结价结算：合并租户尾数后入账完整单位，预留转 settled。
	 */
	SettleResult settle(String reservationId, long actualInputTokens, long actualOutputTokens, Price frozen);

	/**
	 * 按金额结算（非 token 单位：图片/任务/时长，数量×冻结价已由计量器算好）： 合并租户尾数后入账完整单位，预留转 settled。
	 */
	SettleResult settleNano(String reservationId, long actualNano);

	/** 结果未知（上游连接中断等）：预留转 pendingVerify，跨重启保留（第七项确认）。 */
	void markPendingVerify(String reservationId, String reason);

	/** 取消/拒绝：释放预留，不入账（前序安全预留撤回）。 */
	void release(String reservationId, String reason, String operatorId);

	/** 待核验转结算：人工核验后按实际金额入账。 */
	SettleResult settlePending(String reservationId, long actualNano, String reason, String operatorId);

	/** 待核验转释放：核验确认调用失败不收费。 */
	void releasePending(String reservationId, String reason, String operatorId);

	/** 结果未知/待核验的预留（跨重启查询）。 */
	List<Reservation> pendingVerifications(String tenantId);

	/**
	 * 充值入账（凭证防重：同租户同凭证号重复提交返回首次结果）。 负金额等价于手工冲正；受控冲正走 {@link #reverse(ReversalCommand)}。
	 */
	RechargeResult recharge(String tenantId, long amountNano, String voucherNo, String operatorId);

	/** 冲正（第十一项确认）：引用原充值流水整笔负入账，禁止跨租户/重复冲正。 */
	RechargeResult reverse(ReversalCommand command);

	/** 账户快照（余额/透支/尾数）。 */
	Optional<BillingAccount> account(String tenantId);

	/** 在途预留合计（nano）。 */
	long reservedNano(String tenantId);

	/** 设置/调整透支额度（管理操作）。 */
	void setOverdraft(String tenantId, long overdraftNano);

	/** 项目金额子限额（累计额度：已消费 + 在途预留参与准入）。 */
	void setProjectSublimit(String tenantId, String projectId, long limitNano);

	/** 读取项目子限额（未设置返回 empty）。 */
	Optional<Long> projectSublimit(String projectId);

	/** 预留句柄：准入冻结的价格版本与向上取整后的预留金额。 */
	record Reservation(String id, String tenantId, String projectId, String model, long priceVersion, long amountNano) {
	}

	/** 结算结果：postedNano 本次入账（micro 对齐），carryNano 结算后的租户尾数。 */
	record SettleResult(String reservationId, long postedNano, long carryNano) {
	}

	/** 入账结果：凭证防重时 alreadyRecorded=true 且金额为首次入账值。 */
	record RechargeResult(String entryId, String tenantId, long amountNano, String voucherNo, boolean alreadyRecorded) {
	}

}
