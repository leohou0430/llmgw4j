package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 租户计费账户快照（B4/B5/B7）：余额、透支额度与尾数都以 nano-CNY 整数记账； 账务入账粒度为 1 micro-CNY（1000
 * nano），不足单位的尾数按租户持久累计。
 *
 * @param tenantId 租户 id（计费账户主体）
 * @param currency 币种（首版固定 CNY）
 * @param balanceNano 已入账余额（可为负表示欠款）
 * @param overdraftNano 透支额度
 * @param carryNano 尾数累计（0..999），下次结算合并入账
 */
public record BillingAccount(String tenantId, String currency, long balanceNano, long overdraftNano, long carryNano) {
	public BillingAccount {
		Objects.requireNonNull(tenantId, "tenantId");
		if (overdraftNano < 0)
			throw new IllegalArgumentException("overdraft must be >= 0");
		if (carryNano < 0 || carryNano > 999)
			throw new IllegalArgumentException("carry must be in [0,999]");
	}

	/** 计费可用金额 = 余额 + 透支额度（不含在途预留；在途由调用方扣减后比较）。 */
	public long availableNano(long reservedNano) {
		return balanceNano + overdraftNano - reservedNano;
	}
}
