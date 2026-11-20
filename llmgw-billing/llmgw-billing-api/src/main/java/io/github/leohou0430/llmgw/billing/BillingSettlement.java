package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 结算结果：入账金额（完整 micro-CNY 整数）、合并后剩余尾数、账务流水 id。 冻结售价版本记录在案，改价不追溯。
 */
public record BillingSettlement(String callId, String tenantId, long priceVersion, Money postedAmount,
		Money carryRemainder, Money upstreamCostEstimate, long settledAtEpochMs) {
	public BillingSettlement {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(postedAmount, "postedAmount");
		Objects.requireNonNull(carryRemainder, "carryRemainder");
		// 上游成本未知不记为零——独立字段允许 null（ MISSING 标记）。
	}

	public static BillingSettlement of(String callId, String tenantId, long priceVersion, Money posted, Money carry,
			long settledAt) {
		return new BillingSettlement(callId, tenantId, priceVersion, posted, carry, null, settledAt);
	}
}
