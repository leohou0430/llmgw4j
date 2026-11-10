package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single admission rule shared by the in-memory and Redis ledgers. Both
 * implementations (the Redis one in Lua) must agree with this arithmetic;
 * these tests pin it so a pack cannot quietly diverge.
 */
class QuotaMathTest {

    private static UsageLedger.UsageRecord record(boolean success, long prompt, long completion) {
        return new UsageLedger.UsageRecord("key", "model", (int) prompt, (int) completion, 5, success,
                Instant.now(), 100);
    }

    @Test
    void normalizeNeverCreatesCreditFromNegativeEstimates() {
        assertEquals(0, QuotaMath.normalize(-5));
        assertEquals(0, QuotaMath.normalize(0));
        assertEquals(7, QuotaMath.normalize(7));
    }

    @Test
    void admissionRequiresCommittedPlusInFlightPlusWantToSeeminglyFit() {
        assertTrue(QuotaMath.admits(80, 10, 10, 100), "exact fit must be admitted");
        assertFalse(QuotaMath.admits(80, 10, 11, 100), "one over must be rejected");
        assertTrue(QuotaMath.admits(0, 0, 100, 100));
        assertFalse(QuotaMath.admits(1, 0, 100, 100));
        // In-flight reservations count: N concurrent callers cannot all pass.
        assertFalse(QuotaMath.admits(0, 50, 51, 100));
    }

    @Test
    void arithmeticOverflowCannotAdmitOverspending() {
        assertFalse(QuotaMath.admits(Long.MAX_VALUE - 5, 4, 10, Long.MAX_VALUE));
        assertFalse(QuotaMath.admits(0, Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertFalse(QuotaMath.admits(-1, 0, 1, 100));
    }

    @Test
    void onlySuccessfulRequestsAreBillable() {
        assertTrue(QuotaMath.chargeable(record(true, 10, 5)));
        assertFalse(QuotaMath.chargeable(record(false, 10, 5)));
    }

    @Test
    void releaseEqualsExactlyWhatWasReserved() {
        assertEquals(100, QuotaMath.released(100));
        assertEquals(0, QuotaMath.released(-100), "a negative reservation releases nothing");
    }

    @Test
    void billedSumsNormalizedTokenCounts() {
        assertEquals(15, QuotaMath.billed(10, 5));
        assertEquals(0, QuotaMath.billed(-10, -5), "negative usage never bills");
        assertEquals(10, QuotaMath.billed(10, -5));
    }

    @Test
    void costMathIsIntegerExactAtCatalogPrices() {
        var price = new io.github.leohou0430.llmgw.core.model.ModelCatalog.ModelInfo(
                "m", "p", 0, 1_000_000, 2_000_000);
        // 1M input tokens at $1/M = $1; 0.5M output at $2/M = $1 -> $2 total.
        assertEquals(2_000_000, QuotaMath.costMicroUsd(1_000_000, 500_000, price));
        // Small requests round per side: 1 token in + 1 token out = 2 micro-USD.
        assertEquals(3, QuotaMath.costMicroUsd(1, 1, price));
        // Unknown model = $0, never a crash.
        assertEquals(0, QuotaMath.costMicroUsd(1_000, 1_000, null));
        // Negative usage never creates credit.
        assertEquals(0, QuotaMath.costMicroUsd(-5, -5, price));
    }
}
