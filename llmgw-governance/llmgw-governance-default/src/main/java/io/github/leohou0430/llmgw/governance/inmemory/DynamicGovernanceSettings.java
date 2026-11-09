package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * W3.5: the governance knobs that were frozen at construction (rate limit,
 * cache, circuit breaker) become one atomically-swappable snapshot. The
 * limiter / cache / breaker read {@link #current()} per call -- the hot-path
 * cost is one volatile read -- and {@code POST /admin/governance} swaps the
 * whole snapshot via CAS, so no observer ever sees a half-updated mix.
 * Route-table hot reload (v10) keeps its own mechanism; this covers the rest.
 */
public final class DynamicGovernanceSettings {

    public record Snapshot(
            long rateLimitTokensPerSecond,
            long rateLimitBurst,
            int cacheMaxSize,
            int cacheTtlSeconds,
            int circuitFailureThreshold,
            int circuitCooldownMs) {
    }

    private final AtomicReference<Snapshot> ref;
    private final AtomicLong version = new AtomicLong();

    public DynamicGovernanceSettings(GatewayProperties properties) {
        this.ref = new AtomicReference<>(from(properties));
    }

    public Snapshot current() {
        return ref.get();
    }

    /** Monotonic, bumped on every update; caches use it to detect changes. */
    public long version() {
        return version.get();
    }

    /** Null fields keep their current value; values are clamped to sane ranges. */
    public Snapshot update(Long rateLimitTokensPerSecond, Long rateLimitBurst,
                           Integer cacheMaxSize, Integer cacheTtlSeconds,
                           Integer circuitFailureThreshold, Integer circuitCooldownMs) {
        Snapshot old = ref.get();
        Snapshot next = new Snapshot(
                rateLimitTokensPerSecond == null ? old.rateLimitTokensPerSecond()
                        : Math.max(1, rateLimitTokensPerSecond),
                rateLimitBurst == null ? old.rateLimitBurst()
                        : Math.max(1, rateLimitBurst),
                cacheMaxSize == null ? old.cacheMaxSize()
                        : Math.max(1, cacheMaxSize),
                cacheTtlSeconds == null ? old.cacheTtlSeconds()
                        : Math.max(1, cacheTtlSeconds),
                circuitFailureThreshold == null ? old.circuitFailureThreshold()
                        : Math.max(0, circuitFailureThreshold),
                circuitCooldownMs == null ? old.circuitCooldownMs()
                        : Math.max(0, circuitCooldownMs));
        ref.set(next);
        version.incrementAndGet();
        return next;
    }

    private static Snapshot from(GatewayProperties properties) {
        GovernanceLimits limits = GovernanceLimits.from(properties);
        return new Snapshot((long) limits.tokensPerSecond(), limits.bucketCapacity(),
                (int) Math.min(Integer.MAX_VALUE, limits.cacheMaxSize()),
                (int) Math.min(Integer.MAX_VALUE, limits.cacheTtlSeconds()),
                limits.circuitFailureThreshold(),
                (int) Math.min(Integer.MAX_VALUE, limits.circuitCooldownMs()));
    }
}
