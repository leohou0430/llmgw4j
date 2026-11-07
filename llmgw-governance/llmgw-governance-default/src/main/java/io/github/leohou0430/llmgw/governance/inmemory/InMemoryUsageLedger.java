package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.governance.QuotaMath;
import io.github.leohou0430.llmgw.governance.UsageSummaries;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class InMemoryUsageLedger implements UsageLedger {

    private static final class Counters {
        final LongAdder requests = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final LongAdder errors = new LongAdder();
        /** Tokens held by admitted but not-yet-finished requests. */
        final AtomicLong reserved = new AtomicLong();
    }

    private final Map<String, Counters> byKey = new ConcurrentHashMap<>();

    @Override
    public void record(UsageRecord record) {
        Counters counters = byKey.computeIfAbsent(record.keyId(), k -> new Counters());
        counters.requests.increment();
        if (QuotaMath.chargeable(record)) {
            // A failed request produced nothing billable. Keeping the prompt
            // tokens would charge customers for upstream outages.
            counters.promptTokens.add(record.promptTokens());
            counters.completionTokens.add(record.completionTokens());
        } else {
            counters.errors.increment();
        }
        // Release exactly what was held at admission.
        counters.reserved.updateAndGet(v -> Math.max(0, v - QuotaMath.released(record.reservedTokens())));
    }

    @Override
    public Map<String, UsageSummary> snapshot() {
        Map<String, UsageSummary> out = new ConcurrentHashMap<>();
        byKey.forEach((key, c) -> out.put(key, UsageSummaries.of(
                c.requests.sum(), c.promptTokens.sum(), c.completionTokens.sum(), c.errors.sum())));
        return out;
    }

    @Override
    public long used(String keyId) {
        Counters c = byKey.get(keyId);
        return c == null ? 0
                : c.promptTokens.sum() + c.completionTokens.sum() + Math.max(0, c.reserved.get());
    }

    @Override
    public boolean tryReserve(String keyId, long tokens, long quota) {
        long want = QuotaMath.normalize(tokens);
        Counters c = byKey.computeIfAbsent(keyId, k -> new Counters());
        while (true) {
            long committed = c.promptTokens.sum() + c.completionTokens.sum();
            long held = c.reserved.get();
            if (!QuotaMath.admits(committed, held, want, quota)) {
                return false;
            }
            if (c.reserved.compareAndSet(held, held + want)) {
                return true;
            }
        }
    }

    @Override
    public void load(Map<String, UsageSummary> data) {
        // REPLACE, not accumulate: /admin/snapshot/load used to double the
        // counters on every call and eventually 429'd every request.
        data.forEach((key, summary) -> {
            Counters c = new Counters();
            c.requests.add(summary.requests());
            c.promptTokens.add(summary.promptTokens());
            c.completionTokens.add(summary.completionTokens());
            c.errors.add(summary.errors());
            byKey.put(key, c);
        });
    }
}
