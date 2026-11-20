package io.github.leohou0430.llmgw.billing;

/**
 * 八类协议计量器（任务4）：估算（准入预留依据）与提取（结算依据）共享同一 冻结价与单位规则。费用由冻结 {@link Price} 与数量匹配计算——不由客户端
 * 提交金额。缺计价规则抛 {@link MissingPriceException}（调用未发上游即拒绝； 显式免费协议由内置 FREE 单位覆盖）。
 */
public interface BillingMeter {

	/** 协议种类 → 计量单位（八类规则；未知协议按 token 计）。 */
	Unit unitFor(String kind);

	/** 准入估算：返回 ESTIMATED 质量（预留依据）；FREE 单位金额为 0。 */
	MeteredQuantity estimate(Price frozen, BillingInput input);

	/**
	 * Chapter-9 §9.2.6（ADR-0009）严格预算准入：返回可证明的最大可计费消耗。 token 单位要求
	 * {@code input.outputBoundExplicit()}（请求显式输出上界）， 缺失抛
	 * {@link StrictBoundRequiredException}——不可证明的上界不允许伪装 成严格预留。非 token
	 * 单位的数量由请求自身界定（张数/任务数/请求体字节）， 估算即可证明上界。默认实现委托 {@link #estimate}（软预算语义）。
	 */
	default MeteredQuantity worstCase(Price frozen, BillingInput input) {
		return estimate(frozen, input);
	}

	/**
	 * 权威用量提取：evidence.known=false 或辅助单位缺数量时返回 UNKNOWN （挂账待核验，不记零）。
	 */
	MeteredQuantity extract(Price frozen, UsageEvidence evidence, long auxQuantity, Unit auxUnit);

}
