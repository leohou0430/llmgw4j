package io.github.leohou0430.llmgw.governance;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;

import java.util.Objects;

/**
 * One normalized, immutable view of the governance settings for every
 * implementation pack.
 *
 * <p>Before this type existed, the in-memory pack and the Redis pack each read
 * {@code properties.getGovernance()} directly and normalized the same fields
 * differently -- e.g. the in-memory breaker clamped the cooldown to at least
 * 1 ms while the Redis breaker passed the raw value into a Lua {@code PSETEX}.
 * A single pack could therefore be configured identically yet behave
 * differently, which is precisely the kind of silent divergence that makes a
 * single-variable A/B comparison meaningless.
 *
 * <p>Normalization rules (all applied in {@link #from(GatewayProperties)}):
 * capacities and rates are floored at a safe positive value, a non-positive
 * circuit threshold keeps its "breaking disabled" meaning, and cache settings
 * are floored at 1.
 */
public record GovernanceLimits(
        long bucketCapacity,
        double tokensPerSecond,
        int circuitFailureThreshold,
        long circuitCooldownMs,
        long defaultQuotaTokens,
        long cacheMaxSize,
        long cacheTtlSeconds,
        String snapshotPath) {

    public static GovernanceLimits from(GatewayProperties properties) {
        Objects.requireNonNull(properties, "properties");
        GatewayProperties.Governance g = Objects.requireNonNull(
                properties.getGovernance(), "gateway.governance");
        return new GovernanceLimits(
                Math.max(1, g.getRateLimitBurst()),
                Math.max(0, g.getRateLimitTokensPerSecond()),
                g.getCircuitFailureThreshold(),
                Math.max(1, g.getCircuitCooldownMs()),
                Math.max(0, g.getDefaultQuotaTokens()),
                Math.max(1, g.getCacheMaxSize()),
                Math.max(1, g.getCacheTtlSeconds()),
                Objects.requireNonNull(g.getSnapshotPath(), "gateway.governance.snapshot-path"));
    }

    /** Token-bucket refill slope, matched by the Redis Lua script. */
    public double tokensPerNanos() {
        return tokensPerSecond / 1_000_000_000.0;
    }

    /** A non-positive threshold disables breaking in BOTH packs. */
    public boolean circuitEnabled() {
        return circuitFailureThreshold > 0;
    }
}
