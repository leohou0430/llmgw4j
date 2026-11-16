package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.LoginRateLimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window per-key rate limiter: at most {@code maxAttempts} per {@code windowMillis}. */
public final class FixedWindowRateLimiter implements LoginRateLimiter {
    private record Window(long start, int count) {
    }

    private final int maxAttempts;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxAttempts, long windowMillis) {
        if (maxAttempts < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("maxAttempts and windowMillis must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowMillis;
    }

    @Override
    public synchronized boolean allow(String key, long nowEpochMs) {
        windows.entrySet().removeIf(entry -> nowEpochMs - entry.getValue().start() >= windowMillis);
        if (!windows.containsKey(key) && windows.size() >= 4096) {
            return false;
        }
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || nowEpochMs - current.start() >= windowMillis) {
                return new Window(nowEpochMs, 1);
            }
            return new Window(current.start(), Math.min(maxAttempts + 1, current.count() + 1));
        });
        return window.count() <= maxAttempts;
    }
}
