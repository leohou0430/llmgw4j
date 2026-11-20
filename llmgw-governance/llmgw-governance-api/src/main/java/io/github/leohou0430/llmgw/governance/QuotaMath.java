package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;

/**
 * The arithmetic behind in-flight token reservations, shared by the in-memory ledger and
 * the Redis ledger (whose Lua {@code reserve} / {@code settle} scripts implement the same
 * rules).
 *
 * <p>
 * Admission reserves an ESTIMATE; settlement charges the real {@code prompt + completion}
 * usage. A final {@code used} value may therefore exceed the quota by the difference
 * between estimate and actual consumption. That is an intentional semantic, <em>not</em>
 * a distributed oversell: the reservation itself is atomic in both packs.
 */
public final class QuotaMath {

	private QuotaMath() {
	}

	/** Never let a negative estimate create credit. */
	public static long normalize(long tokens) {
		return Math.max(0, tokens);
	}

	/** The single admission rule: committed + in-flight + want must fit. */
	public static boolean admits(long committed, long reserved, long want, long quota) {
		return committed >= 0 && reserved >= 0 && want >= 0 && quota >= 0 && committed <= quota
				&& reserved <= quota - committed && want <= quota - committed - reserved;
	}

	/** A failed request produced nothing billable (no charge for an outage). */
	public static boolean chargeable(UsageLedger.UsageRecord record) {
		return record.success();
	}

	/** Exactly what was held at admission must be released, success or failure. */
	public static long released(long reservedTokens) {
		return normalize(reservedTokens);
	}

	public static long billed(long promptTokens, long completionTokens) {
		return Math.addExact(normalize(promptTokens), normalize(completionTokens));
	}

	/**
	 * v13 $ math: micro-USD cost of one request from its token counts and the catalog
	 * price (micro-USD per 1M tokens, per side). Integer-only: checked long fast path
	 * with an exact big-integer fallback, rounded half-up per side; unrepresentable final
	 * amounts throw. A null / unknown price costs nothing -- budget enforcement simply
	 * does not see the model until it is cataloged.
	 */
	public static long costMicroUsd(long promptTokens, long completionTokens,
			io.github.leohou0430.llmgw.core.model.ModelCatalog.ModelInfo price) {
		if (price == null) {
			return 0L;
		}
		return Math.addExact(scaled(normalize(promptTokens), price.inputPriceMicroUsdPerMillion()),
				scaled(normalize(completionTokens), price.outputPriceMicroUsdPerMillion()));
	}

	private static long scaled(long tokens, long microUsdPerMillion) {
		if (tokens <= 0 || microUsdPerMillion <= 0) {
			return 0L;
		}
		if (tokens <= (Long.MAX_VALUE - 500_000L) / microUsdPerMillion) {
			return (tokens * microUsdPerMillion + 500_000L) / 1_000_000L;
		}
		// Large intermediate products may still have a representable, correctly rounded
		// result.
		return java.math.BigInteger.valueOf(tokens)
			.multiply(java.math.BigInteger.valueOf(microUsdPerMillion))
			.add(java.math.BigInteger.valueOf(500_000))
			.divide(java.math.BigInteger.valueOf(1_000_000))
			.longValueExact();
	}

}
