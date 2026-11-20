package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.billing.UsageEvidence;

/**
 * 单次真实上游尝试的计费句柄。由 {@link BillableCall#beginAttempt} 分配， 只能属于创建它的 call（构造私有）；生命周期方法全部回调
 * call 推进账本， 终态推进与结算幂等由 call 与账本状态机共同保证。
 */
public final class BillableAttempt {

	private final BillableCall owner;

	private final String attemptId;

	private final String routeId;

	private final String sourceVersion;

	private volatile boolean submitted;

	private volatile String upstreamRequestId;

	BillableAttempt(BillableCall owner, String attemptId, String routeId, String sourceVersion) {
		this.owner = owner;
		this.attemptId = attemptId;
		this.routeId = routeId;
		this.sourceVersion = sourceVersion;
	}

	public String attemptId() {
		return attemptId;
	}

	public String routeId() {
		return routeId;
	}

	public String sourceVersion() {
		return sourceVersion;
	}

	/** 上游请求是否已发出（此后失败不得静默释放，须挂账或按证据结算）。 */
	public boolean submitted() {
		return submitted;
	}

	public String upstreamRequestId() {
		return upstreamRequestId;
	}

	/** 上游请求已发出（此后失败不得静默释放，须挂账或按证据结算）。 */
	public void markSubmitted(String upstreamRequestId) {
		this.upstreamRequestId = upstreamRequestId;
		this.submitted = true;
	}

	/** 终态推进：outcome 取值见 {@link BillableCall} 集中合法值表。 */
	public void complete(UsageEvidence evidence, String outcome) {
		owner.completeAttempt(this, evidence, outcome);
	}

	/** 非 token 单位终态（八类协议）：金额已由计量器按冻结价算好，账本按金额结算。 */
	public void completeNano(long actualNano, String outcome) {
		owner.completeNanoAttempt(this, actualNano, outcome);
	}

	/** 结果未知（已提交但未收到完整响应）：挂账待核验，跨重启保留。 */
	public void markUnknown(String reason) {
		owner.markUnknownAttempt(this, reason);
	}

}
