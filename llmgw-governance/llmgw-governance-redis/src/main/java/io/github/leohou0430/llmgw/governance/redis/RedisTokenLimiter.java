package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/** Cross-replica token bucket: the whole refill-and-debit is one Lua script. */
public final class RedisTokenLimiter implements TokenLimiter {

    private final JedisPooled redis;
    private final RedisScripts scripts;
    private final String prefix;
    private final long capacity;
    private final double ratePerSecond;

    public RedisTokenLimiter(JedisPooled redis, RedisScripts scripts,
                             GatewayProperties properties) {
        this(redis, scripts, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX,
                GovernanceLimits.from(properties));
    }

    private RedisTokenLimiter(JedisPooled redis, RedisScripts scripts,
                             String prefix, GovernanceLimits limits) {
        this(redis, scripts, prefix, limits.bucketCapacity(), limits.tokensPerSecond());
    }

    public RedisTokenLimiter(JedisPooled redis, RedisScripts scripts,
                             String prefix, long capacity, double ratePerSecond) {
        this.redis = redis;
        this.scripts = scripts;
        this.prefix = prefix;
        this.capacity = capacity;
        this.ratePerSecond = ratePerSecond;
    }

    private String key(String keyId) {
        return prefix + "bucket:" + keyId;
    }

    @Override
    public boolean tryAcquire(String keyId, int tokens) {
        return acquire(keyId, tokens, capacity, ratePerSecond);
    }

    @Override
    public boolean tryAcquire(String keyId, int tokens, long tokensPerSecond, long burst) {
        return acquire(keyId, tokens, Math.max(1, burst > 0 ? burst : tokensPerSecond),
                Math.max(0, tokensPerSecond));
    }

    private boolean acquire(String keyId, int tokens, long bucketCapacity, double bucketRate) {
        Long allowed = scripts.execute(redis, scripts.acquire, List.of(key(keyId)),
                Long.toString(bucketCapacity), Double.toString(bucketRate),
                Long.toString(System.currentTimeMillis()),
                Integer.toString(Math.max(1, tokens)));
        return allowed != null && allowed == 1L;
    }

    @Override
    public void refund(String keyId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        scripts.execute(redis, scripts.refund, List.of(key(keyId)),
                Long.toString(capacity), Integer.toString(tokens));
    }

    @Override
    public long remaining(String keyId) {
        Object value = redis.hget(key(keyId), "tokens");
        if (value == null) {
            return (long) capacity;
        }
        try {
            return (long) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
