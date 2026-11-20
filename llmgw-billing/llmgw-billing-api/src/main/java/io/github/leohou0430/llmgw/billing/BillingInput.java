package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 协议无关的已分类计费输入（任务4）：引擎在准入时从已解析请求提取，不含 原始正文。估算输出 token 缺省按 1024 上限。
 *
 * <p>
 * Chapter-9 §9.2.6（ADR-0009）：{@code outputBoundExplicit} 标记输出上界 是否来自请求的显式参数（max_tokens /
 * max_output_tokens / max_completion_tokens）。严格预算模式的预留必须可证明——只有显式上界才
 * 构成「受约束的最大输出」；缺省估计（1024）只是软预算语义。
 *
 * @param kind 协议种类（决定计量单位与计价公式）
 * @param estimatedInputTokens 输入 token 估计（准入 token 数）
 * @param estimatedOutputTokens 输出 token 估计（max_tokens 或缺省）
 * @param units 单位数量（IMAGES 的 n；其余 1）
 * @param bytes 请求体字节（音频/文件类估算时长）
 * @param outputBoundExplicit 输出上界是否来自请求显式参数
 */
public record BillingInput(String kind, long estimatedInputTokens, long estimatedOutputTokens, long units, long bytes,
		boolean outputBoundExplicit) {
	public BillingInput {
		Objects.requireNonNull(kind, "kind");
		if (estimatedInputTokens < 0 || estimatedOutputTokens < 0 || units < 0 || bytes < 0) {
			throw new IllegalArgumentException("billing input quantities must be non-negative");
		}
	}

	/** 兼容构造器：未声明显式输出上界（缺省估计语义）。 */
	public BillingInput(String kind, long estimatedInputTokens, long estimatedOutputTokens, long units, long bytes) {
		this(kind, estimatedInputTokens, estimatedOutputTokens, units, bytes, false);
	}

	public static BillingInput tokenCall(String kind, long estimatedInputTokens, long estimatedOutputTokens) {
		return new BillingInput(kind, estimatedInputTokens, estimatedOutputTokens, 1, 0, false);
	}

	/** 显式输出上界的 token 调用（严格预算准入依据，Chapter-9 §9.2.6）。 */
	public static BillingInput tokenCallBounded(String kind, long estimatedInputTokens, long outputBoundTokens) {
		return new BillingInput(kind, estimatedInputTokens, outputBoundTokens, 1, 0, true);
	}
}
