package io.github.leohou0430.llmgw.core.governance;

/** Token-level rate limiting (not QPS). Implementations must be atomic and lock-free. */
public interface TokenLimiter {
    boolean tryAcquire(String keyId, int tokens);

    /**
     * B4: rate-aware variant for per-model buckets (each model can carry its
     * own tokens-per-second and burst). Implementations that only support a
     * global rate may fall back to {@link #tryAcquire(String, int)} -- the
     * per-model limits then degrade to sharing the default bucket shape.
     */
    default boolean tryAcquire(String keyId, int tokens, long tokensPerSecond, long burst) {
        return tryAcquire(keyId, tokens);
    }

    /**
     * Return tokens that were charged at admission but never consumed (failed
     * request, cancelled downstream, or a cache hit that never touched the
     * upstream). Without this, failures and aborts silently drain the bucket.
     */
    void refund(String keyId, int tokens);

    long remaining(String keyId);
}
