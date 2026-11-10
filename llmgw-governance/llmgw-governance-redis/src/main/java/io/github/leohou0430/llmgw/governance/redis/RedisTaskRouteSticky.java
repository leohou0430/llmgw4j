package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;


/**
 * W3.6/W4.3: cross-replica task stickiness. A job created on replica A is
 * pollable from replica B: taskId -> routeId lives in Redis with the same TTL
 * the in-memory map uses. SETEX/GET — one round trip per bind/resolve.
 */
public final class RedisTaskRouteSticky implements TaskRouteSticky {

    private final JedisPooled redis;
    private final String prefix;
    private final long ttlSeconds;

    public RedisTaskRouteSticky(JedisPooled redis, long ttlSeconds) {
        this(redis, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX, ttlSeconds);
    }

    public RedisTaskRouteSticky(JedisPooled redis, String prefix, long ttlSeconds) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    @Override
    public void bind(String taskId, String routeId) {
        if (taskId == null || taskId.isBlank() || routeId == null || routeId.isBlank()) {
            return;
        }
        redis.setex(prefix + "sticky:" + taskId, ttlSeconds, routeId);
    }

    @Override
    public String resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return redis.get(prefix + "sticky:" + taskId);
    }
}
