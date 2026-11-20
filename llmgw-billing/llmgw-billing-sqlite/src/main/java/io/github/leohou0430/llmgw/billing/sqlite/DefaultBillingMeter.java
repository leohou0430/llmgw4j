package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MeteredQuantity;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.StrictBoundRequiredException;
import io.github.leohou0430.llmgw.billing.Unit;
import io.github.leohou0430.llmgw.billing.UsageEvidence;

/**
 * 八类协议计价（任务4）：协议种类 → 计量单位 → 冻结价公式。缺计价规则抛
 * {@link MissingPriceException}（准入即拒绝、未发上游）；FILES/BATCHES 管理 面内置 FREE。结算入账粒度与账本一致：向上取整到
 * whole micro。
 */
public final class DefaultBillingMeter implements BillingMeter {

	/** 音频估时码率（bytes → seconds，下限 1 秒）。 */
	private static final long AUDIO_BYTES_PER_SECOND = 32_000;

	/** 输出 token 缺省估计（准入无 max_tokens 时）。 */
	private static final long DEFAULT_OUTPUT_TOKENS = 1024;

	/**
	 * Chapter-9 §9.2.6（ADR-0009）：严格预算的可证明上界。token 单位必须携带
	 * 请求显式输出上界（{@code outputBoundExplicit}）——「最大值并不可靠」时 拒绝而非静默回软。非 token
	 * 单位的数量由请求自身界定（张数/任务数/请求 体字节即完整输入），估算即可证明上界。
	 */
	@Override
	public MeteredQuantity worstCase(Price frozen, BillingInput input) {
		if (unitOf(input.kind()) == Unit.TOKENS && !input.outputBoundExplicit()) {
			throw new StrictBoundRequiredException(frozen.model());
		}
		return estimate(frozen, input);
	}

	@Override
	public MeteredQuantity estimate(Price frozen, BillingInput input) {
		Unit unit = unitOf(input.kind());
		if (unit == Unit.FREE) {
			return new MeteredQuantity(Unit.FREE, 0, 0, MeteredQuantity.Quality.AUTHORITATIVE);
		}
		return switch (unit) {
			case TOKENS -> {
				if (frozen.inputNanoPerMillion() == 0 && frozen.outputNanoPerMillion() == 0) {
					throw new MissingPriceException(frozen.model(), frozen.version());
				}
				long in = Math.max(1, input.estimatedInputTokens());
				long out = input.estimatedOutputTokens() > 0 ? input.estimatedOutputTokens() : DEFAULT_OUTPUT_TOKENS;
				long nano = tokensNano(frozen, in, out);
				yield new MeteredQuantity(Unit.TOKENS, in + out, nano, MeteredQuantity.Quality.ESTIMATED);
			}
			case IMAGES -> {
				if (frozen.nanoPerImage() == 0) {
					throw new MissingPriceException(frozen.model(), frozen.version());
				}
				long units = Math.max(1, input.units());
				yield new MeteredQuantity(Unit.IMAGES, units, roundMicro(units * frozen.nanoPerImage()),
						MeteredQuantity.Quality.ESTIMATED);
			}
			case TASKS -> {
				if (frozen.nanoPerTask() == 0) {
					throw new MissingPriceException(frozen.model(), frozen.version());
				}
				yield new MeteredQuantity(Unit.TASKS, 1, roundMicro(frozen.nanoPerTask()),
						MeteredQuantity.Quality.ESTIMATED);
			}
			case SECONDS -> {
				if (frozen.nanoPerSecond() == 0) {
					throw new MissingPriceException(frozen.model(), frozen.version());
				}
				long seconds = Math.max(1, input.bytes() / AUDIO_BYTES_PER_SECOND);
				yield new MeteredQuantity(Unit.SECONDS, seconds, roundMicro(seconds * frozen.nanoPerSecond()),
						MeteredQuantity.Quality.ESTIMATED);
			}
			case BYTES, FREE -> new MeteredQuantity(unit, 0, 0, MeteredQuantity.Quality.ESTIMATED);
		};
	}

	@Override
	public MeteredQuantity extract(Price frozen, UsageEvidence evidence, long auxQuantity, Unit auxUnit) {
		if (!evidence.known()) {
			return MeteredQuantity.unknown(Unit.TOKENS);
		}
		Unit unit = auxUnit == null ? Unit.TOKENS : auxUnit;
		return switch (unit) {
			case TOKENS -> {
				long nano = tokensNano(frozen, evidence.inputTokens(), evidence.outputTokens());
				yield new MeteredQuantity(Unit.TOKENS, evidence.inputTokens() + evidence.outputTokens(), nano,
						MeteredQuantity.Quality.AUTHORITATIVE);
			}
			case IMAGES -> new MeteredQuantity(Unit.IMAGES, auxQuantity,
					roundMicro(auxQuantity * frozen.nanoPerImage()), MeteredQuantity.Quality.AUTHORITATIVE);
			case TASKS -> new MeteredQuantity(Unit.TASKS, 1, roundMicro(frozen.nanoPerTask()),
					MeteredQuantity.Quality.AUTHORITATIVE);
			case SECONDS -> new MeteredQuantity(Unit.SECONDS, auxQuantity,
					roundMicro(auxQuantity * frozen.nanoPerSecond()), MeteredQuantity.Quality.AUTHORITATIVE);
			case BYTES, FREE -> new MeteredQuantity(unit, auxQuantity, 0, MeteredQuantity.Quality.AUTHORITATIVE);
		};
	}

	@Override
	public Unit unitFor(String kind) {
		return unitOf(kind);
	}

	private static Unit unitOf(String kind) {
		return switch (kind) {
			case "chat", "responses", "messages", "embeddings", "rerank" -> Unit.TOKENS;
			case "images" -> Unit.IMAGES;
			case "videos" -> Unit.TASKS;
			case "speech", "transcriptions" -> Unit.SECONDS;
			case "files", "batches" -> Unit.FREE;
			default -> Unit.TOKENS; // 未知协议按 token 计（缺价仍拒绝）
		};
	}

	/** token 计价：nano 对齐 whole micro（入账单位）。 */
	private static long tokensNano(Price frozen, long inputTokens, long outputTokens) {
		long raw = (inputTokens * frozen.inputNanoPerMillion() + outputTokens * frozen.outputNanoPerMillion() + 999_999)
				/ 1_000_000;
		return roundMicro(raw);
	}

	private static long roundMicro(long nano) {
		return (nano + 999) / 1000 * 1000;
	}

}
