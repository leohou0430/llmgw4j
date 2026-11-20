package io.github.leohou0430.llmgw.billing;

import java.util.Optional;

/**
 * 实际用量证据：上游 usage 帧为权威；未知结果（结果未核实）无证据， 预留跨重启保留待对账（不自动记零或自动释放）。 证据质量由 {@code known}
 * 标记：known=false 时 input/output 值无意义。
 */
public record UsageEvidence(boolean known, long inputTokens, long outputTokens) {
	public static UsageEvidence known(long inputTokens, long outputTokens) {
		return new UsageEvidence(true, inputTokens, outputTokens);
	}

	public static UsageEvidence unknown() {
		return new UsageEvidence(false, 0, 0);
	}

	public Optional<long[]> asKnown() {
		return known ? Optional.of(new long[] { inputTokens, outputTokens }) : Optional.empty();
	}
}
