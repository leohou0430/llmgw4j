package io.github.leohou0430.llmgw.core.governance;

import java.util.Map;

/**
 * Per-key $ spend ledger (v13). The hard-budget counterpart of the token
 * quota: admission RESERVES an estimated micro-USD amount atomically, settle
 * books the real cost. {@code "花费到限额即拒绝"} is what separates a gateway
 * from a pure observability product.
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
     * (failures / cache hits book nothing and release everything).
     */
    void settle(String keyId, long reservedMicroUsd, long actualMicroUsd);

    /** Committed spend per key, for snapshots and the admin surface. */
    Map<String, Long> spendMicroUsd();
}
