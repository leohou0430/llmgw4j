package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free token bucket. Refill is computed lazily from nanoTime, no background thread.
 *
 * <p>W3.5: capacity and refill rate are read PER CALL from the shared
 * {@link DynamicGovernanceSettings} snapshot, so {@code POST /admin/governance}
 * retunes the limiter without a restart. Existing buckets keep their token
 * count; the new capacity applies on the next refill/clamp.
 *
 * <p>B4: {@link #tryAcquire(String, int, long, long)} lets a caller pass a
 * per-bucket rate shape (per-model buckets); the shape is stored on the bucket
 * (volatile) so subsequent refills use it until the next shape update.
 */
public class AtomicTokenLimiter implements TokenLimiter {

    private static final class Bucket {
        final AtomicLong tokens;
        final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());
        volatile long capacity;
        volatile double tokensPerNanos;

        Bucket(long initial, long capacity, double tokensPerNanos) {
            this.tokens = new AtomicLong(initial);
            this.capacity = capacity;
            this.tokensPerNanos = tokensPerNanos;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final DynamicGovernanceSettings settings;
    /** Fixed snapshot when constructed without the dynamic holder (compat). */
    private final DynamicGovernanceSettings fixedSettings;

    public AtomicTokenLimiter(GatewayProperties properties) {
        this(properties, null);
    }

    public AtomicTokenLimiter(GatewayProperties properties, DynamicGovernanceSettings settings) {
        GovernanceLimits limits = GovernanceLimits.from(properties);
        this.fixedSettings = new DynamicGovernanceSettings(properties);
        this.settings = settings == null ? fixedSettings : settings;
    }

    private long capacity() {
        return settings.current().rateLimitBurst();
    }

    private double tokensPerNanos() {
        return settings.current().rateLimitTokensPerSecond() / 1_000_000_000.0;
    }

    @Override
    public boolean tryAcquire(String keyId, int tokens) {
        // W3.5 contract: the key bucket re-reads the dynamic snapshot EVERY
        // call, so POST /admin/governance retunes existing buckets too.
        Bucket bucket = buckets.computeIfAbsent(keyId,
                k -> new Bucket(capacity(), capacity(), tokensPerNanos()));
        bucket.capacity = capacity();
        bucket.tokensPerNanos = tokensPerNanos();
        refill(bucket, bucket.capacity, bucket.tokensPerNanos);
        return consume(bucket, tokens);
    }

    @Override
    public boolean tryAcquire(String keyId, int tokens, long tokensPerSecond, long burst) {
        long cap = Math.max(1, burst > 0 ? burst : tokensPerSecond);
        double rate = Math.max(0, tokensPerSecond) / 1_000_000_000.0;
        Bucket bucket = buckets.computeIfAbsent(keyId, k -> new Bucket(cap, cap, rate));
        // Shape update applies from this call onward (volatile write).
        bucket.capacity = cap;
        bucket.tokensPerNanos = rate;
        refill(bucket, cap, rate);
        return consume(bucket, tokens);
    }

    private void refill(Bucket bucket, long capacityNow, double refillRate) {
        long now = System.nanoTime();
        long last = bucket.lastRefillNanos.get();
        long elapsed = now - last;
        long refill = elapsed > 0 ? (long) (elapsed * refillRate) : 0;
        // Only advance the timestamp when at least one whole token was minted.
        // The old code advanced it even when the integer cast truncated refill
        // to 0, permanently discarding that elapsed interval -- at high call
        // rates (small elapsed, low rate) the bucket never refilled at all.
        // A losing CAS also leaves the timestamp untouched, so no time is lost.
        if (refill > 0 && bucket.lastRefillNanos.compareAndSet(last, now)) {
            bucket.tokens.updateAndGet(current -> Math.min(capacityNow, current + refill));
        }
    }

    private boolean consume(Bucket bucket, int tokens) {
        int want = Math.max(0, tokens);
        while (true) {
            long current = bucket.tokens.get();
            if (current < want) {
                return false;
            }
            if (bucket.tokens.compareAndSet(current, current - want)) {
                return true;
            }
        }
    }

    @Override
    public void refund(String keyId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        Bucket bucket = buckets.get(keyId);
        if (bucket != null) {
            bucket.tokens.updateAndGet(current -> Math.min(bucket.capacity, current + tokens));
        }
    }

    @Override
    public long remaining(String keyId) {
        Bucket bucket = buckets.get(keyId);
        return bucket == null ? capacity() : Math.max(0, bucket.tokens.get());
    }
}
