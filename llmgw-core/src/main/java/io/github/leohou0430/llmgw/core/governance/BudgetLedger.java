package io.github.leohou0430.llmgw.core.governance;

import java.util.Map;

/**
 * Per-key estimated $ spend ledger (demo). Admission reserves an estimated
 * micro-USD amount atomically; settlement
 * books reported cost. Production requires durable recovery and proven cost bounds;
 * the built-in implementation does not provide those guarantees.
 *
 * <p>Units are micro-USD (1e-6 $) end to end -- longs, no floating point.
 * Budget 0 = disabled (the key is unmetered in $ terms).
 */
public interface BudgetLedger {

    /**
     * Atomic admission: committed-spend + in-flight-reservation + estimate
     * must fit the budget. false = deny with 429 spend-budget-exceeded.
     */
    boolean tryReserve(String keyId, long estimatedMicroUsd, long budgetMicroUsd);

    /**
     * Settlement: release the reservation and book the actual spend
     * (cache hits and known unsent failures release everything). Unknown upstream
     * outcomes must retain the reservation until reconciled.
     */
    void settle(String keyId, long reservedMicroUsd, long actualMicroUsd);

    /** Committed spend per key, for snapshots and the admin surface. */
    Map<String, Long> spendMicroUsd();
}
