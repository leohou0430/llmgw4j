package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 准入预留记录（ADR-0007）：按估算费用向上取整冻结，状态机 reserved → settled | pendingVerify | released。重复
 * callId 防重由 幂等键层处理，本记录不承担。
 */
public record BillingReservation(String callId, String tenantId, String projectId, String model, long priceVersion,
		Money reservedAmount, long createdAtEpochMs) {
	public BillingReservation {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(reservedAmount, "reservedAmount");
	}
}
