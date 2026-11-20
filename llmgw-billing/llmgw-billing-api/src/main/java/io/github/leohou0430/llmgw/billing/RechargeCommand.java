package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 充值入账命令（第十/十一项确认）：管理员核实收款后手工入账，凭证防重 （重复提交同凭证号不重复加款），纠错通过冲正流水处理。
 */
public record RechargeCommand(String tenantId, long amountNano, String voucherNo, String operatorId,
		String operationId) {
	public RechargeCommand {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(voucherNo, "voucherNo");
		Objects.requireNonNull(operatorId, "operatorId");
		Objects.requireNonNull(operationId, "operationId");
		if (amountNano <= 0)
			throw new IllegalArgumentException("recharge must be positive");
	}
}
