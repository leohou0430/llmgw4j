package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 冲正命令（第十一项确认）：引用原充值流水，负金额入账，不直接修改余额； 引用的原流水必须存在且尚未被冲正。
 */
public record ReversalCommand(String originalEntryId, String tenantId, String operatorId, String reason,
		String operationId) {
	public ReversalCommand {
		Objects.requireNonNull(originalEntryId, "originalEntryId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(operatorId, "operatorId");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(operationId, "operationId");
	}
}
