package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/**
 * Shared open/half-open state. The open marker has a TTL equal to the cooldown,
 * and half-open probing is a SETNX lease so only ONE replica sends a probe.
 */
public final class RedisCircuitBreaker implements CircuitBreaker {

    private final JedisPooled redis;
    private final RedisScripts scripts;
    private final String prefix;
    private final int threshold;
    private final long cooldownMs;
    private final boolean circuitEnabled;

    public RedisCircuitBreaker(JedisPooled redis, RedisScripts scripts,
                               GatewayProperties properties) {
        this(redis, scripts, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX,
                GovernanceLimits.from(properties));
    }

    private RedisCircuitBreaker(JedisPooled redis, RedisScripts scripts,
                               String prefix, GovernanceLimits limits) {
        this(redis, scripts, prefix, limits.circuitFailureThreshold(),
                limits.circuitCooldownMs(), limits.circuitEnabled());
    }

    public RedisCircuitBreaker(JedisPooled redis, RedisScripts scripts,
                               String prefix, int threshold, long cooldownMs) {
        this(redis, scripts, prefix, threshold, cooldownMs, threshold > 0);
    }

    private RedisCircuitBreaker(JedisPooled redis, RedisScripts scripts,
                               String prefix, int threshold, long cooldownMs,
                               boolean circuitEnabled) {
        this.redis = redis;
        this.scripts = scripts;
        this.prefix = prefix;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.circuitEnabled = circuitEnabled;
    }

    private String failures(Route route) { return prefix + "cb:fail:" + route.id(); }
    private String open(Route route) { return prefix + "cb:open:" + route.id(); }
    private String probe(Route route) { return prefix + "cb:probe:" + route.id(); }

    @Override
    public boolean allow(Route route) {
        if (!circuitEnabled) {
            return true;
        }
        Long allowed = scripts.execute(redis, scripts.breakerAllow,
                List.of(open(route), probe(route)));
        return allowed == null || allowed == 1L;
    }

    @Override
    public void recordSuccess(Route route) {
        redis.del(failures(route), open(route), probe(route));
    }

    @Override
    public void recordFailure(Route route) {
        if (!circuitEnabled) {
            return;
        }
        scripts.execute(redis, scripts.breakerFailure,
                List.of(failures(route), open(route)),
                Integer.toString(threshold), Long.toString(cooldownMs));
    }
}
