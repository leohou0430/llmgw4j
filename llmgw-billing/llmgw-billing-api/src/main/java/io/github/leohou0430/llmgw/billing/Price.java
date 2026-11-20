package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * B2/B10 冻结的客戶售价版本（ADR-0007）：价格独立于上游成本，每次调用固定准入时 的版本。token 单位为 nano-CNY per million
 * tokens；非 token 单位为 nano-CNY / 单位。单价 0 = 该单位未定价（{@link MissingPriceException} 拒绝；显式免费
 * 协议由 {@code BillingMeter} 的内置 FREE 单位覆盖）。
 *
 * @param version 售价版本号（同一模型可多版本并存，准入时冻结）
 * @param inputNanoPerMillion 输入价格（nano-CNY / 百万 token）
 * @param outputNanoPerMillion 输出价格（nano-CNY / 百万 token）
 * @param frozenAt 版本冻结时间（epoch ms）
 * @param nanoPerImage 图片单价（nano-CNY / 张；0 = 未定价）
 * @param nanoPerTask 异步任务单价（nano-CNY / 任务；0 = 未定价）
 * @param nanoPerSecond 时长单价（nano-CNY / 秒；0 = 未定价）
 */
public record Price(long version, String model, long inputNanoPerMillion, long outputNanoPerMillion, long frozenAt,
		long nanoPerImage, long nanoPerTask, long nanoPerSecond) {
	public Price {
		Objects.requireNonNull(model, "model");
		if (inputNanoPerMillion < 0 || outputNanoPerMillion < 0 || nanoPerImage < 0 || nanoPerTask < 0
				|| nanoPerSecond < 0) {
			throw new IllegalArgumentException("prices must be non-negative");
		}
	}

	/** 兼容构造器：只含 token 单价（非 token 单位未定价）。 */
	public Price(long version, String model, long inputNanoPerMillion, long outputNanoPerMillion, long frozenAt) {
		this(version, model, inputNanoPerMillion, outputNanoPerMillion, frozenAt, 0, 0, 0);
	}
}
