package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

public record ReconciliationCommand(String callId, ReconciliationAction action, long actualNano, String reason,
		String operatorId, String operationId) {
	public ReconciliationCommand {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(operatorId, "operatorId");
		Objects.requireNonNull(operationId, "operationId");
		if (action == ReconciliationAction.SETTLE && actualNano < 0) {
			throw new IllegalArgumentException("settle amount must be >= 0");
		}
	}
}
