package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 客户售价版本（第十九项反馈）：价格独立于上游成本，版本化，改价不追溯。 计价单位 nano-CNY per million tokens。价格由操作者显式配置，零价必须显式
 * （FREE 标记），未知成本不记为零。
 */
public record PriceVersion(String model, long version, long inputNanoPerMillion, long outputNanoPerMillion,
		boolean explicitFree, long frozenAtEpochMs) {
	public PriceVersion {
		Objects.requireNonNull(model, "model");
		if (version < 1)
			throw new IllegalArgumentException("version must be >= 1");
		if (inputNanoPerMillion < 0 || outputNanoPerMillion < 0) {
			throw new IllegalArgumentException("prices must be non-negative (explicit zero is FREE)");
		}
		if (!explicitFree && inputNanoPerMillion == 0 && outputNanoPerMillion == 0) {
			throw new IllegalArgumentException("zero price requires explicitFree=true (缺价拒绝，不隐式记零)");
		}
	}

	/** 该输入/输出 token 组合的费用（nano-CNY，整数精确，向上取整到 nano）。 */
	public Money costNano(long inputTokens, long outputTokens) {
		long in = Math.multiplyExact(Math.max(0, inputTokens), inputNanoPerMillion);
		long out = Math.multiplyExact(Math.max(0, outputTokens), outputNanoPerMillion);
		long total = Math.addExact(in, out);
		return Money.ofNanoCny(total);
	}
}
