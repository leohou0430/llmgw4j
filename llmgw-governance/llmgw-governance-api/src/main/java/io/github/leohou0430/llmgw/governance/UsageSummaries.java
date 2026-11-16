package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;

/** Shared construction / read-back helpers for the {@code UsageSummary} DTO. */
public final class UsageSummaries {

    /** Largest integer represented exactly by Redis Lua's numeric arithmetic. */
    public static final long MAX_EXACT_COUNTER = 9_007_199_254_740_991L;

    private UsageSummaries() {
    }

    public static UsageLedger.UsageSummary of(long requests, long promptTokens,
                                             long completionTokens, long errors) {
        return new UsageLedger.UsageSummary(requests, promptTokens, completionTokens, errors);
    }

    public static long billed(UsageLedger.UsageSummary summary) {
        return QuotaMath.billed(summary.promptTokens(), summary.completionTokens());
    }

    /** Snapshot counters must not create negative credit or overflow billed totals. */
    public static void validateSnapshot(java.util.Map<String, UsageLedger.UsageSummary> data) {
        for (var entry : data.entrySet()) {
            var value = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank() || value == null
                    || value.requests() < 0 || value.promptTokens() < 0
                    || value.completionTokens() < 0 || value.errors() < 0) {
                throw new IllegalArgumentException("snapshot requires nonnegative counters and nonblank key ids");
            }
            if (value.requests() > MAX_EXACT_COUNTER || value.errors() > MAX_EXACT_COUNTER
                    || value.promptTokens() > MAX_EXACT_COUNTER || value.completionTokens() > MAX_EXACT_COUNTER
                    || value.promptTokens() > MAX_EXACT_COUNTER - value.completionTokens()) {
                throw new IllegalArgumentException("snapshot counters exceed the supported exact integer range");
            }
        }
    }

    /**
     * Tolerant numeric read for values coming back from an external store
     * (Redis hashes store numbers as strings). Returns 0 for null/garbage
     * rather than throwing into the request path.
     */
    public static long number(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
