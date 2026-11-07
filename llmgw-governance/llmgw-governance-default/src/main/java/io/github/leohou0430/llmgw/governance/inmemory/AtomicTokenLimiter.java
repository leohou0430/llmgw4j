package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free token bucket. Refill is computed lazily from nanoTime, no background thread. */
public class AtomicTokenLimiter implements TokenLimiter {

    private static final class Bucket {
        final AtomicLong tokens;
        final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());
        Bucket(long initial) { this.tokens = new AtomicLong(initial); }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final double tokensPerNanos;

    public AtomicTokenLimiter(GatewayProperties properties) {
        GovernanceLimits limits = GovernanceLimits.from(properties);
        this.capacity = limits.bucketCapacity();
        this.tokensPerNanos = limits.tokensPerNanos();
    }

    @Override
    public boolean tryAcquire(String keyId, int tokens) {
        Bucket bucket = buckets.computeIfAbsent(keyId, k -> new Bucket(capacity));
        long now = System.nanoTime();
        long last = bucket.lastRefillNanos.get();
        long elapsed = now - last;
        long refill = elapsed > 0 ? (long) (elapsed * tokensPerNanos) : 0;
        // Only advance the timestamp when at least one whole token was minted.
        // The old code advanced it even when the integer cast truncated refill
        // to 0, permanently discarding that elapsed interval -- at high call
        // rates (small elapsed, low rate) the bucket never refilled at all.
        // A losing CAS also leaves the timestamp untouched, so no time is lost.
        if (refill > 0 && bucket.lastRefillNanos.compareAndSet(last, now)) {
            bucket.tokens.updateAndGet(current -> Math.min(capacity, current + refill));
        }
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
            bucket.tokens.updateAndGet(current -> Math.min(capacity, current + tokens));
        }
    }

    @Override
    public long remaining(String keyId) {
        Bucket bucket = buckets.get(keyId);
        return bucket == null ? capacity : Math.max(0, bucket.tokens.get());
    }
}
