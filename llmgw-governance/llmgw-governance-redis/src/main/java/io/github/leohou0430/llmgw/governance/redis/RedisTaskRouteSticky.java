package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * W3.6/W4.3: cross-replica task stickiness. A job created on replica A is
 * pollable from replica B: taskId -> routeId lives in Redis with the same TTL
 * the in-memory map uses. SETEX/GET — one round trip per bind/resolve.
 */
public final class RedisTaskRouteSticky implements TaskRouteSticky {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final long ttlSeconds;

    public RedisTaskRouteSticky(StringRedisTemplate redis, long ttlSeconds) {
        this(redis, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX, ttlSeconds);
    }

    public RedisTaskRouteSticky(StringRedisTemplate redis, String prefix, long ttlSeconds) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    @Override
    public void bind(String taskId, String routeId) {
        if (taskId == null || taskId.isBlank() || routeId == null || routeId.isBlank()) {
            return;
        }
        redis.opsForValue().set(prefix + "sticky:" + taskId, routeId,
                Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return redis.opsForValue().get(prefix + "sticky:" + taskId);
    }
}
