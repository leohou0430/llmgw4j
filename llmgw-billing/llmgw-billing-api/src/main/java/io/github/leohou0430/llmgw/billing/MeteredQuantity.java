package io.github.leohou0430.llmgw.billing;

/**
 * 计量结果（任务4）：单位、数量与证据质量三要素。费用 = 冻结价 × 数量， 由网关计算——不由客户端提交金额。UNKNOWN 质量的数量无意义且金额为 0，
 * 表示结果未知须挂账待核验（不自动记零）。
 *
 * @param unit 计量单位
 * @param quantity 数量（单位 {@link Unit} 的语义）
 * @param nano 该数量的 nano-CNY 金额（UNKNOWN 时为 0）
 * @param quality 证据质量
 */
public record MeteredQuantity(Unit unit, long quantity, long nano, Quality quality) {

	public enum Quality {

		/** 准入估算（预留依据，结算前）。 */
		ESTIMATED,
		/** 上游权威用量帧。 */
		AUTHORITATIVE,
		/** 结果未知（挂账待核验）。 */
		UNKNOWN

	}

	public MeteredQuantity {
		if (quantity < 0 || nano < 0) {
			throw new IllegalArgumentException("metered quantities must be non-negative");
		}
		if (quality == Quality.UNKNOWN && nano != 0) {
			throw new IllegalArgumentException("unknown evidence carries no amount");
		}
	}

	public static MeteredQuantity unknown(Unit unit) {
		return new MeteredQuantity(unit, 0, 0, Quality.UNKNOWN);
	}
}
