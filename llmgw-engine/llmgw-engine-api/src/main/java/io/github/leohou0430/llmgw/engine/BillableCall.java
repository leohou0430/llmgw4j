package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MeteredQuantity;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.billing.UsageEvidence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 计费调用生命周期（ADR-0007）：包装 {@link BillingLedger}，为每次真实上游 attempt
 * 分配内部标识、绑定准入时冻结的售价；终态推进经账本持久状态机保证 「最终 attempt 仅结算一次」。客户端 requestId 与 HTTP 成功不作为计费
 * 幂等/已知用量依据；结果未知的预留跨重启挂账待核验。
 *
 * <p>
 * 状态机：OPEN → SETTLED | PENDING_VERIFY | RELEASED，终态只能推进一次。 billing pack 缺席（demo）时返回 no-op
 * call，请求照常转发不计费。
 *
 * <p>
 * 金额准入在冻结售价后原子预留（预留向上取整到 whole micro）；计费可用 金额不足时 {@link #open} 返回 null——调用方必须拒绝且不发上游。价格版本
 * 在途期间更新不影响本调用（改价不追溯）。
 */
public final class BillableCall {

	/** outcome 集中合法值表：{@code complete} 校验，不由控制器任意拼写。 */
	public static final String OUTCOME_SETTLED = "settled";

	public static final String OUTCOME_RELEASED = "released";

	public static final String OUTCOME_PENDING_VERIFY = "pending-verify";

	private static final Set<String> OUTCOMES = Set.of(OUTCOME_SETTLED, OUTCOME_RELEASED, OUTCOME_PENDING_VERIFY);

	private final BillingLedger ledger; // null = billing disabled

	private final Price frozen; // null = billing disabled

	private final BillingLedger.Reservation reservation; // null = billing disabled

	private final Map<String, BillableAttempt> attempts = new LinkedHashMap<>();

	private String finalAttemptId;

	private boolean finished;

	private BillableCall(BillingLedger ledger, Price frozen, BillingLedger.Reservation reservation) {
		this.ledger = ledger;
		this.frozen = frozen;
		this.reservation = reservation;
	}

	/**
	 * 金额准入：冻结该模型当前售价，由 {@link BillingMeter} 按协议计量单位估算 （缺计价规则抛
	 * {@link MissingPriceException}——调用方捕获后拒绝，未发上游）， 再原子预留。计费可用金额不足返回
	 * null（拒绝是正常路径）。billing pack 缺席（任一依赖为 null）时返回 no-op call。
	 */
	public static BillableCall open(BillingLedger ledger, PriceBook prices, BillingMeter meter, BillingInput input,
			String tenantId, String projectId, String model) {
		return open(ledger, prices, meter, input, tenantId, projectId, model, false);
	}

	/**
	 * Chapter-9 §9.2.6（ADR-0009）严格预算准入：{@code strict=true} 时预留依据 是
	 * {@link BillingMeter#worstCase}——可证明的最大可计费消耗；token 调用缺 请求显式输出上界抛
	 * {@link StrictBoundRequiredException}（拒绝，不静默回软）， UNKNOWN
	 * 证据同样不可预留（结果未知的消耗无法设界）。软预算（默认）沿用 估算预留语义。
	 */
	public static BillableCall open(BillingLedger ledger, PriceBook prices, BillingMeter meter, BillingInput input,
			String tenantId, String projectId, String model, boolean strict) {
		if (ledger == null || prices == null || meter == null) {
			return new BillableCall(null, null, null);
		}
		Price frozen = prices.freeze(model);
		MeteredQuantity estimate = strict ? meter.worstCase(frozen, input) : meter.estimate(frozen, input);
		if (strict && estimate.quality() == MeteredQuantity.Quality.UNKNOWN) {
			throw new io.github.leohou0430.llmgw.billing.StrictBoundRequiredException(model);
		}
		long estimateNano = estimate.quality() == MeteredQuantity.Quality.UNKNOWN ? 0 : estimate.nano();
		BillingLedger.Reservation reservation = ledger.reserve(tenantId, projectId, model, frozen.version(),
				estimateNano);
		if (reservation == null) {
			return null; // 金额拒绝：不得发上游
		}
		return new BillableCall(ledger, frozen, reservation);
	}

	/** 计费是否启用（billing pack 在位）。 */
	public boolean enabled() {
		return reservation != null;
	}

	/** 终态是否已推进（结算/挂账/释放任一）。 */
	public boolean finished() {
		return finished;
	}

	/** 准入冻结的售价版本（版本化：改价不追溯在途）。 */
	public Price frozenPrice() {
		return frozen;
	}

	/** 账本预留标识（billingCallId）。 */
	public String billingCallId() {
		return reservation == null ? null : reservation.id();
	}

	/** 为本次真实上游尝试分配内部标识（任意 attempt 只属于一个 call）。 */
	public BillableAttempt beginAttempt(String routeId, String sourceVersion) {
		BillableAttempt attempt = new BillableAttempt(this, "att-" + UUID.randomUUID(), routeId, sourceVersion);
		attempts.put(attempt.attemptId(), attempt);
		return attempt;
	}

	/** 选定最终服务 attempt；只能选定一次（CAS），重复即失败。 */
	public void selectFinalAttempt(String attemptId) {
		if (finalAttemptId != null) {
			throw new IllegalStateException("final attempt already selected: " + finalAttemptId);
		}
		if (attemptId != null && !attempts.containsKey(attemptId)) {
			throw new IllegalArgumentException("unknown attempt: " + attemptId);
		}
		finalAttemptId = attemptId;
	}

	/**
	 * 前序安全预留撤回：请求从未提交上游（准入后发送前失败/取消），释放 预留不入账。终态后撤回是幂等 no-op。
	 */
	public void releaseUnsubmitted(String reason) {
		if (finished) {
			return;
		}
		finished = true;
		if (reservation != null) {
			ledger.release(reservation.id(), reason, "engine");
		}
	}

	/** 终态推进（由 {@link BillableAttempt} 回调；终态只推进一次）。 */
	void completeAttempt(BillableAttempt attempt, UsageEvidence evidence, String outcome) {
		requireOwned(attempt);
		if (!OUTCOMES.contains(outcome)) {
			throw new IllegalArgumentException("unknown outcome: " + outcome);
		}
		if (finished) {
			throw new IllegalStateException("call already finished: " + attempt.attemptId());
		}
		finished = true;
		if (reservation == null) {
			return; // billing disabled
		}
		switch (outcome) {
			case OUTCOME_SETTLED -> {
				if (evidence != null && evidence.known()) {
					// 账本持久 CAS：预留只在 reserved 态可结算，重复结算由状态机拒绝。
					ledger.settle(reservation.id(), evidence.inputTokens(), evidence.outputTokens(), frozen);
				}
				else {
					// 已知用量缺席不记零：挂账待核验（结果未知保留预留）。
					ledger.markPendingVerify(reservation.id(), "settled without known usage evidence");
				}
			}
			case OUTCOME_RELEASED -> ledger.release(reservation.id(), "attempt released", "engine");
			case OUTCOME_PENDING_VERIFY -> ledger.markPendingVerify(reservation.id(), "outcome pending verify");
			default -> throw new IllegalStateException("unreachable outcome");
		}
	}

	/** 结果未知（已提交上游但连接中断）：预留挂账跨重启保留。 */
	void markUnknownAttempt(BillableAttempt attempt, String reason) {
		requireOwned(attempt);
		if (finished) {
			return; // 已有终态优先
		}
		finished = true;
		if (reservation != null) {
			ledger.markPendingVerify(reservation.id(), reason);
		}
	}

	/** 非 token 单位终态（八类协议）：金额已由计量器按冻结价算好，账本按金额结算。 */
	void completeNanoAttempt(BillableAttempt attempt, long actualNano, String outcome) {
		requireOwned(attempt);
		if (!OUTCOMES.contains(outcome)) {
			throw new IllegalArgumentException("unknown outcome: " + outcome);
		}
		if (finished) {
			throw new IllegalStateException("call already finished: " + attempt.attemptId());
		}
		finished = true;
		if (reservation == null) {
			return; // billing disabled
		}
		switch (outcome) {
			case OUTCOME_SETTLED -> {
				if (actualNano > 0) {
					ledger.settleNano(reservation.id(), actualNano);
				}
				else {
					// 零金额（内置免费单位）：无费用释放预留。
					ledger.release(reservation.id(), "no chargeable amount", "engine");
				}
			}
			case OUTCOME_RELEASED -> ledger.release(reservation.id(), "attempt released", "engine");
			case OUTCOME_PENDING_VERIFY -> ledger.markPendingVerify(reservation.id(), "outcome pending verify");
			default -> throw new IllegalStateException("unreachable outcome");
		}
	}

	private void requireOwned(BillableAttempt attempt) {
		if (attempt == null || attempts.get(attempt.attemptId()) != attempt) {
			throw new IllegalArgumentException("attempt does not belong to this call");
		}
	}

}
