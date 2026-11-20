package io.github.leohou0430.llmgw.approval;

import java.util.List;
import java.util.Optional;

/**
 * 审批工作流（Chapter-9 §9.5.4）。高风险工具或模型操作在执行前先提交审批， 审批未决不得执行（调用方挂起业务；HTTP 不长挂——提交即返回审批标识与查询
 * 路径，等待与通知由业务侧承接）。
 *
 * <p>
 * 状态机：{@code PENDING → APPROVED | REJECTED | EXPIRED}，
 * {@code APPROVED → REVOKED}。REJECTED/EXPIRED/REVOKED 为终态；迁移全部为 持久 CAS（WHERE
 * state='PENDING' 等），并发或重复回调只成功一次，不会产生 重复副作用（决策以状态机为准）。
 * </p>
 *
 * <p>
 * 边界语义：
 * </p>
 * <ul>
 * <li>批准 ≠ 放行：执行前必须 {@link #revalidate} 重校验——主体一致、参数 摘要一致、未过期、未撤销、状态 APPROVED。revalidate
 * 幂等可重复调用， 不改变状态。</li>
 * <li>审批超时不自动降级为只读：EXPIRED 只表示该次审批失效，不把目标工具或 操作改写为只读模式，也不自动重开审批。</li>
 * <li>拒绝不隐式替换工具/参数：REJECTED 不换工具、不改参数、不降级重试， 重新执行必须重新走审批。</li>
 * <li>结果未知交由业务查询/对账（网关不重试）：审批推进本身幂等，重复回调/ 重试以状态机为准，不产生重复决策或重复撤销。</li>
 * </ul>
 */
public interface ApprovalService {

	/** 审批状态：PENDING 未决；APPROVED 已批准（可被 REVOKED）；其余为终态。 */
	enum State {

		PENDING, APPROVED, REJECTED, EXPIRED, REVOKED

	}

	/**
	 * 审批记录快照。{@code decidedBy}/{@code decidedAtEpochMs}/{@code
	 * decisionReason} 记录终态达成信息：APPROVED/REJECTED 为决策人与决策时间； REVOKED
	 * 为撤销人与撤销时间（决策原因列承载撤销原因）；EXPIRED 的 decidedBy/decisionReason 为 null、decidedAtEpochMs
	 * 为过期观测时间。
	 */
	record Approval(String id, String subjectRef, String tenantId, String projectId, String targetKind,
			String targetName, String toolVersion, String paramsDigest, String risk, long createdAtEpochMs,
			long expiresAtEpochMs, State state, String decidedBy, long decidedAtEpochMs, String decisionReason) {
	}

	/**
	 * 提交审批：创建 PENDING 记录并返回快照；有效期 {@code expiresAtEpochMs =
	 * nowEpochMs + ttlMs} 在提交时冻结。审批未决不得执行——调用方挂起业务， HTTP 不长挂。paramsDigest 为参数摘要（SHA-256
	 * 由调用方算好传入），本服务 不解析参数原文。
	 * @param subjectRef 执行主体引用（如代理身份/服务账号），非空
	 * @param tenantId 租户，非空
	 * @param projectId 项目，非空
	 * @param targetKind 目标类型（如 mcp-tool / model-op），非空
	 * @param targetName 目标名称（工具或操作名），非空
	 * @param toolVersion 工具版本，非空
	 * @param paramsDigest 参数摘要（调用方算好），非空
	 * @param risk 风险标签（如 low/medium/high），非空
	 * @param ttlMs 有效期时长（毫秒），必须为正
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 * @return 新建审批快照（state=PENDING）；任一必填参数为空或 ttlMs 非正时抛 IllegalArgumentException
	 */
	Approval submit(String subjectRef, String tenantId, String projectId, String targetKind, String targetName,
			String toolVersion, String paramsDigest, String risk, long ttlMs, long nowEpochMs);

	/** 按标识读取审批快照；不存在返回 empty。 */
	Optional<Approval> get(String id);

	/** 租户待决审批列表（state=PENDING，按提交时间升序）；不含其他状态。 */
	List<Approval> pending(String tenantId);

	/**
	 * 决策（持久 CAS，仅 PENDING 可决）：
	 * <ul>
	 * <li>PENDING 且未过期 → CAS 迁移到 APPROVED 或 REJECTED，记录决策人、 时间与原因；并发决策只有一次成功。</li>
	 * <li>PENDING 但已过期 → 先 CAS 转为 EXPIRED，再拒绝本次决策（决策不 生效），返回 EXPIRED 快照。</li>
	 * <li>已处于终态 → 幂等返回原状态快照，不重复决策、不翻转结论。</li>
	 * </ul>
	 * @param id 审批标识
	 * @param approve true=批准；false=拒绝
	 * @param decidedBy 决策人，非空
	 * @param reason 决策原因，非空
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 * @return 决策后的审批快照（被拒绝的过期决策返回 EXPIRED 快照）；审批不存在 抛 NoSuchElementException，决策人或原因为空抛
	 * IllegalArgumentException
	 */
	Approval decide(String id, boolean approve, String decidedBy, String reason, long nowEpochMs);

	/**
	 * 执行前重校验（Chapter-9 §9.5.4 批准 ≠ 放行）：主体一致、参数摘要一致、 未过期、未撤销、状态 APPROVED 全部满足才返回
	 * true；任一不满足返回 false。 幂等只读，可重复调用，不改变状态；重复执行方回调以此为唯一放行依据。
	 */
	boolean revalidate(String id, String subjectRef, String paramsDigest, long nowEpochMs);

	/**
	 * 撤销（持久 CAS，仅 APPROVED 可撤销）：APPROVED → REVOKED，记录撤销人、 时间与原因；撤销后 revalidate 一律
	 * false。幂等：已 REVOKED 重复撤销返回 原快照；PENDING/REJECTED/EXPIRED 状态撤销为 no-op 返回原快照。
	 * @param id 审批标识
	 * @param revokedBy 撤销人，非空
	 * @param reason 撤销原因，非空
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 * @return 撤销后的审批快照；审批不存在抛 NoSuchElementException，撤销人或 原因为空抛 IllegalArgumentException
	 */
	Approval revoke(String id, String revokedBy, String reason, long nowEpochMs);

}
