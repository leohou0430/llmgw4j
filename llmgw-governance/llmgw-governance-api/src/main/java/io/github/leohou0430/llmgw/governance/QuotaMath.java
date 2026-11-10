package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;

/**
 * The arithmetic behind in-flight token reservations, shared by the in-memory
 * ledger and the Redis ledger (whose Lua {@code reserve} / {@code settle}
 * scripts implement the same rules).
 *
 * <p>Admission reserves an ESTIMATE; settlement charges the real
 * {@code prompt + completion} usage. A final {@code used} value may therefore
 * exceed the quota by the difference between estimate and actual consumption.
 * That is an intentional semantic, <em>not</em> a distributed oversell: the
 * reservation itself is atomic in both packs.
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
        return committed >= 0 && reserved >= 0 && want >= 0 && quota >= 0
                && committed <= quota && reserved <= quota - committed
                && want <= quota - committed - reserved;
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
        return normalize(promptTokens) + normalize(completionTokens);
    }

    /**
     * v13 $ math: micro-USD cost of one request from its token counts and the
     * catalog price (micro-USD per 1M tokens, per side). Integer-only:
     * tokens x price fits a long for any realistic request (1e6 x 1e9 = 1e15),
     * rounded half-up per side to keep repeated settlements drift-free.
     * A null / unknown price costs nothing -- budget enforcement simply does
     * not see the model until it is cataloged.
     */
    public static long costMicroUsd(long promptTokens, long completionTokens,
                                     io.github.leohou0430.llmgw.core.model.ModelCatalog.ModelInfo price) {
        if (price == null) {
            return 0L;
        }
        return scaled(normalize(promptTokens), price.inputPriceMicroUsdPerMillion())
                + scaled(normalize(completionTokens), price.outputPriceMicroUsdPerMillion());
    }

    private static long scaled(long tokens, long microUsdPerMillion) {
        if (tokens <= 0 || microUsdPerMillion <= 0) {
            return 0L;
        }
        return (tokens * microUsdPerMillion + 500_000) / 1_000_000;
    }
}
