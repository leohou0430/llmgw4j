package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import redis.clients.jedis.*;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/** Optional native Redis pack. It replaces definitions before any dependent graph is built. */
public final class RedisGovernanceModule implements ExtensionModule {
    @Override public void configure(ExtensionContext c) {
        var p = c.get(GatewayProperties.class);
        c.bind(JedisPooled.class, () -> {
            var r = p.getRedis();
            var config = DefaultJedisClientConfig.builder().database(r.getDatabase()).ssl(r.isSsl())
                    .connectionTimeoutMillis(r.getTimeoutMs()).socketTimeoutMillis(r.getTimeoutMs());
            if (r.getUsername() != null && !r.getUsername().isBlank()) config.user(r.getUsername());
            if (r.getPassword() != null && !r.getPassword().isBlank()) config.password(r.getPassword());
            var pool = new GenericObjectPoolConfig<Connection>();
            pool.setMaxTotal(r.getPoolSize());pool.setMaxIdle(r.getPoolSize());
            pool.setMaxWait(java.time.Duration.ofMillis(r.getTimeoutMs()));
            return JedisPooled.builder().hostAndPort(r.getHost(), r.getPort()).clientConfig(config.build()).poolConfig(pool).build();
        });
        c.bind(RedisScripts.class, RedisScripts::new);
        c.replace(TokenLimiter.class, () -> new RedisTokenLimiter(c.get(JedisPooled.class), c.get(RedisScripts.class), p));
        c.replace(UsageLedger.class, () -> new RedisUsageLedger(c.get(JedisPooled.class), c.get(RedisScripts.class)));
        c.replace(ApiKeyRegistry.class, () -> new RedisApiKeyRegistry(c.get(JedisPooled.class), p));
        c.replace(SemanticCache.class, () -> new RedisSemanticCache(c.get(JedisPooled.class), GovernanceLimits.from(p)));
        c.replace(TaskRouteSticky.class, () -> new RedisTaskRouteSticky(c.get(JedisPooled.class), p.getVideos().getStickyTtlSeconds()));
        c.replace(CircuitBreaker.class, () -> new RedisCircuitBreaker(c.get(JedisPooled.class), c.get(RedisScripts.class), p));
    }
}
