package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.BudgetLedger;
import io.github.leohou0430.llmgw.governance.QuotaMath;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory $ ledger mirroring the token ledger's semantics: an atomic
 * committed+reserved admission check and a settle that books real spend.
 * Reservations live per key so N concurrent callers cannot all slip past a
 * budget (the exact failure mode QuotaMath.admits exists to prevent).
 */
public class InMemoryBudgetLedger implements BudgetLedger {

    private static final class Spend {
        long committedMicroUsd;
        long reservedMicroUsd;
    }

    private final Map<String, Spend> byKey = new ConcurrentHashMap<>();

    private Spend spend(String keyId) {
        return byKey.computeIfAbsent(keyId, ignored -> new Spend());
    }

    @Override
    public boolean tryReserve(String keyId, long estimatedMicroUsd, long budgetMicroUsd) {
        if (budgetMicroUsd <= 0) {
            return true; // no budget configured: unmetered
        }
        long want = QuotaMath.normalize(estimatedMicroUsd);
        Spend spend = spend(keyId);
        synchronized (spend) {
            if (!QuotaMath.admits(spend.committedMicroUsd, spend.reservedMicroUsd, want, budgetMicroUsd)) {
                return false;
            }
            spend.reservedMicroUsd += want;
            return true;
        }
    }

    @Override
    public void settle(String keyId, long reservedMicroUsd, long actualMicroUsd) {
        Spend spend = spend(keyId);
        long released = QuotaMath.released(reservedMicroUsd);
        long billable = QuotaMath.normalize(actualMicroUsd);
        synchronized (spend) {
            if (released > spend.reservedMicroUsd) {
                throw new IllegalArgumentException("settlement exceeds held reservation");
            }
            long committed = Math.addExact(spend.committedMicroUsd, billable);
            spend.reservedMicroUsd -= released;
            spend.committedMicroUsd = committed;
        }
    }

    @Override
    public Map<String, Long> spendMicroUsd() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        byKey.forEach((key, spend) -> {
            synchronized (spend) { out.put(key, spend.committedMicroUsd); }
        });
        return out;
    }
}
