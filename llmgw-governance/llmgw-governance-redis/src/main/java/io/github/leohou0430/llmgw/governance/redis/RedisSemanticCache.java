package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * W3.6: cross-replica response cache. GET/SETEX with the same TTL the
 * in-memory pack uses; the key function is injected exactly as in-memory
 * (exact hash by default, embedding-similarity when W4.2's key lands), so the
 * cache STRATEGY stays swappable while the STORAGE becomes shared.
 */
public final class RedisSemanticCache implements SemanticCache {

    private final JedisPooled redis;
    private final String prefix;
    private final long ttlSeconds;

    public RedisSemanticCache(JedisPooled redis, GovernanceLimits limits) {
        this(redis, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX, limits.cacheTtlSeconds());
    }

    public RedisSemanticCache(JedisPooled redis, String prefix, long ttlSeconds) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    private String key(String cacheKey) {
        return prefix + "cache:" + cacheKey;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.get(key(key)));
    }

    @Override
    public void put(String key, String value) {
        redis.setex(key(key), ttlSeconds, value);
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
        var options = new redis.clients.jedis.params.ScanParams().match(prefix + "cache:*").count(500);
        String cursor = "0";
        do {
            var page = redis.scan(cursor, options);
            if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
            cursor = page.getCursor();
        } while (!"0".equals(cursor));
    }
}
