package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;

/** Shared construction / read-back helpers for the {@code UsageSummary} DTO. */
public final class UsageSummaries {

    private UsageSummaries() {
    }

    public static UsageLedger.UsageSummary of(long requests, long promptTokens,
                                             long completionTokens, long errors) {
        return new UsageLedger.UsageSummary(requests, promptTokens, completionTokens, errors);
    }

    public static long billed(UsageLedger.UsageSummary summary) {
        return QuotaMath.billed(summary.promptTokens(), summary.completionTokens());
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
            return (long) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
