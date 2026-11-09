package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * W3.6: cross-replica response cache. GET/SETEX with the same TTL the
 * in-memory pack uses; the key function is injected exactly as in-memory
 * (exact hash by default, embedding-similarity when W4.2's key lands), so the
 * cache STRATEGY stays swappable while the STORAGE becomes shared.
 */
public final class RedisSemanticCache implements SemanticCache {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final long ttlSeconds;

    public RedisSemanticCache(StringRedisTemplate redis, GovernanceLimits limits) {
        this(redis, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX, limits.cacheTtlSeconds());
    }

    public RedisSemanticCache(StringRedisTemplate redis, String prefix, long ttlSeconds) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    private String key(String cacheKey) {
        return prefix + "cache:" + cacheKey;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key(key)));
    }

    @Override
    public void put(String key, String value) {
        redis.opsForValue().set(key(key), value, Duration.ofSeconds(ttlSeconds));
    }

    /** Approximate: SCAN-free cardinality of the cache keyspace is not cheap,
     * so the admin surface shows -1 (unknown) instead of a misleading count. */
    @Override
    public int size() {
        return -1;
    }

    /** B8: SCAN + batch delete of this gateway's cache keyspace. */
    @Override
    public void invalidateAll() {
        var options = org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(prefix + "cache:*").count(500).build();
        List<String> keys = new ArrayList<>();
        try (var cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
