package io.github.leohou0430.llmgw.governance.inmemory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GovernanceMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public GovernanceMetrics(MeterRegistry registry) { this.registry = registry; }
    private MeterRegistry registry() { return registry; }

    public void count(String name, String... tags) {
        MeterRegistry target = registry();
        if (target == null) {
            return;
        }
        String key = tags.length == 0 ? name : name + String.join("/", tags);
        // get() fast path avoids allocating a capturing lambda on every hit;
        // computeIfAbsent is only entered on the first occurrence of a key.
        Counter counter = counters.get(key);
        if (counter == null) {
            counter = counters.computeIfAbsent(key,
                    k -> Counter.builder(name).tags(tags).register(target));
        }
        counter.increment();
    }

    public void latency(String name, long millis) {
        MeterRegistry target = registry();
        if (target == null) {
            return;
        }
        // Rebuilding the Timer on every request allocates a builder and walks
        // the registry's meter map. Cache it like the counters do.
        Timer timer = timers.get(name);
        if (timer == null) {
            timer = timers.computeIfAbsent(name, k -> Timer.builder(k)
                    .publishPercentileHistogram().register(target));
        }
        timer.record(Duration.ofMillis(millis));
    }

    /**
     * v10 AI observability: nanosecond precision (a mock TTFT is well below
     * 1ms, so millisecond recording would flatten it to zero) with optional
     * tags. Client-side percentiles are enabled so /admin/metrics shows real
     * p50/p99 instead of NaN on a SimpleMeterRegistry.
     */
    public void latencyNanos(String name, long nanos, String... tags) {
        MeterRegistry target = registry();
        if (target == null || nanos < 0) {
            return;
        }
        String key = tags.length == 0 ? name : name + String.join("/", tags);
        Timer timer = timers.get(key);
        if (timer == null) {
            timer = timers.computeIfAbsent(key, k -> Timer.builder(name)
                    .tags(tags)
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.99)
                    .register(target));
        }
        timer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
