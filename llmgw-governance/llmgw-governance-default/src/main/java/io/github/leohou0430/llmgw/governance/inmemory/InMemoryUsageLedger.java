package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.governance.QuotaMath;
import io.github.leohou0430.llmgw.governance.UsageSummaries;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class InMemoryUsageLedger implements UsageLedger {
    private static final class Counters {
        long requests, promptTokens, completionTokens, errors, reserved;
    }
    private final Map<String, Counters> byKey = new ConcurrentHashMap<>();
    // Normal operations remain concurrent across keys; restore excludes all of them.
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    @Override public void record(UsageRecord record) {
        var gate = lifecycle.readLock(); gate.lock();
        try {
            Counters c = byKey.computeIfAbsent(record.keyId(), k -> new Counters());
            synchronized (c) {
                // Settlement transfers reserved credit into committed usage atomically.
                c.requests++;
                if (QuotaMath.chargeable(record)) {
                    c.promptTokens += record.promptTokens();
                    c.completionTokens += record.completionTokens();
                } else c.errors++;
                c.reserved = Math.max(0, c.reserved - QuotaMath.released(record.reservedTokens()));
            }
        } finally { gate.unlock(); }
    }

    @Override public Map<String, UsageSummary> snapshot() {
        var gate = lifecycle.readLock(); gate.lock();
        try {
            Map<String, UsageSummary> out = new HashMap<>();
            byKey.forEach((key, c) -> {
                synchronized (c) { out.put(key, UsageSummaries.of(c.requests, c.promptTokens, c.completionTokens, c.errors)); }
            });
            return out;
        } finally { gate.unlock(); }
    }

    @Override public long used(String keyId) {
        var gate = lifecycle.readLock(); gate.lock();
        try {
            Counters c = byKey.get(keyId);
            if (c == null) return 0;
            synchronized (c) { return c.promptTokens + c.completionTokens + c.reserved; }
        } finally { gate.unlock(); }
    }

    @Override public boolean tryReserve(String keyId, long tokens, long quota) {
        long want = QuotaMath.normalize(tokens);
        var gate = lifecycle.readLock(); gate.lock();
        try {
            Counters c = byKey.computeIfAbsent(keyId, k -> new Counters());
            synchronized (c) {
                if (!QuotaMath.admits(c.promptTokens + c.completionTokens, c.reserved, want, quota)) return false;
                c.reserved += want;
                return true;
            }
        } finally { gate.unlock(); }
    }

    @Override public void load(Map<String, UsageSummary> data) {
        Map<String, UsageSummary> supplied = Map.copyOf(data);
        UsageSummaries.validateSnapshot(supplied);
        Map<String, Counters> restored = new HashMap<>();
        supplied.forEach((key, value) -> {
            Counters c = new Counters();c.requests=value.requests();c.promptTokens=value.promptTokens();
            c.completionTokens=value.completionTokens();c.errors=value.errors();restored.put(key,c);
        });
        var gate = lifecycle.writeLock(); gate.lock();
        try {
            for (String key : restored.keySet()) {
                Counters current = byKey.get(key);
                if (current != null && current.reserved != 0) throw new IllegalStateException("cannot restore usage with active reservations");
            }
            // Replace supplied records, preserving unrelated IDs, as in the original in-memory contract.
            byKey.putAll(restored);
        } finally { gate.unlock(); }
    }
}
