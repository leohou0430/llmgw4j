package io.github.leohou0430.llmgw.core.governance;

/** Token-level rate limiting (not QPS). Implementations must be atomic and lock-free. */
public interface TokenLimiter {
    boolean tryAcquire(String keyId, int tokens);

    /**
     * Return tokens that were charged at admission but never consumed (failed
     * request, cancelled downstream, or a cache hit that never touched the
     * upstream). Without this, failures and aborts silently drain the bucket.
     */
    void refund(String keyId, int tokens);

    long remaining(String keyId);
}
