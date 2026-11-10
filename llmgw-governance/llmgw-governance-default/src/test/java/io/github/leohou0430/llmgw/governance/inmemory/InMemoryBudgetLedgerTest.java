package io.github.leohou0430.llmgw.governance.inmemory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The $ admission rule mirrors the token quota: atomic committed+reserved
 * check, settle books real spend, failures release everything. This is the
 * arithmetic that makes "hard budget stop" actually hard under concurrency.
 */
class InMemoryBudgetLedgerTest {

    private final InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();

    @Test
    void admissionReservesAndSettleBooks() {
        // budget $1.00 = 1_000_000 micro-USD
        assertTrue(ledger.tryReserve("k", 400_000, 1_000_000));
        assertTrue(ledger.tryReserve("k", 500_000, 1_000_000),
                "in-flight reservations count: 400k committed-soon + 500k fits");
        assertFalse(ledger.tryReserve("k", 200_000, 1_000_000),
                "400k+500k+200k > 1M must deny");

        ledger.settle("k", 400_000, 350_000);
        // After settle: committed 350k, still-reserved 500k -> 150k now fits
        // (350 + 500 + 150 = 1000 exactly at the cap).
        assertTrue(ledger.tryReserve("k", 150_000, 1_000_000));
        assertEquals(350_000, ledger.spendMicroUsd().get("k"));
    }

    @Test
    void failureSettleReleasesEverythingAndBooksNothing() {
        assertTrue(ledger.tryReserve("k", 800_000, 1_000_000));
        ledger.settle("k", 800_000, 0);
        assertEquals(0, ledger.spendMicroUsd().getOrDefault("k", 0L));
        assertTrue(ledger.tryReserve("k", 1_000_000, 1_000_000),
                "released reservation frees the full budget");
    }

    @Test
    void concurrentSettlementNeverCreatesFreeBudget() throws Exception {
        int count = 20000;
        for (int i = 0; i < count; i++) assertTrue(ledger.tryReserve("race-" + i, 100, 100));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var extra = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var settle = pool.submit(() -> {
                for (int i = 0; i < count; i++) {
                    barrier.await(); ledger.settle("race-" + i, 100, 100); barrier.await();
                }
                return null;
            });
            var admit = pool.submit(() -> {
                for (int i = 0; i < count; i++) {
                    barrier.await();
                    if (ledger.tryReserve("race-" + i, 100, 100)) extra.incrementAndGet();
                    barrier.await();
                }
                return null;
            });
            settle.get(30, java.util.concurrent.TimeUnit.SECONDS);
            admit.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0, extra.get(), "settlement must not expose released funds before charging them");
    }

    @Test
    void invalidSettlementCannotCorruptHeldFunds() {
        assertTrue(ledger.tryReserve("k", 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ledger.settle("k", 101, 0));
        assertFalse(ledger.tryReserve("k", 1, 100));
        ledger.settle("k", 100, 100);
        assertFalse(ledger.tryReserve("k", 1, 100));
    }

    @Test
    void zeroBudgetMeansUnmetered() {
        for (int i = 0; i < 100; i++) {
            assertTrue(ledger.tryReserve("k", Long.MAX_VALUE / 4, 0));
        }
    }
}
