package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Opt-in distributed governance pack.
 *
 * <p>Registered through AutoConfiguration.imports in THIS module, which is only
 * on the classpath when the app is built with {@code -Predis}. Every bean is
 * guarded by {@code @ConditionalOnMissingBean} on the SPI type and ordered
 * BEFORE the default pack (see GovernancePacks), so the in-memory defaults back off
 * cleanly and the engines / core remain unaware of Redis.
 */
@AutoConfiguration
@AutoConfigureBefore(name = GovernancePacks.DEFAULT_AUTO_CONFIGURATION)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = GovernancePacks.REDIS_PROPERTY_PREFIX,
        name = GovernancePacks.REDIS_PROPERTY_NAME, havingValue = "true")
public class RedisGovernanceConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisScripts.class)
    public RedisScripts redisScripts() {
        return new RedisScripts();
    }

    @Bean
    @ConditionalOnMissingBean(TokenLimiter.class)
    public RedisTokenLimiter redisTokenLimiter(StringRedisTemplate redis,
                                               RedisScripts scripts,
                                               GatewayProperties properties) {
        return new RedisTokenLimiter(redis, scripts, properties);
    }

    @Bean
    @ConditionalOnMissingBean(UsageLedger.class)
    public RedisUsageLedger redisUsageLedger(StringRedisTemplate redis,
                                             RedisScripts scripts) {
        return new RedisUsageLedger(redis, scripts);
    }

    @Bean
    @ConditionalOnMissingBean(CircuitBreaker.class)
    public RedisCircuitBreaker redisCircuitBreaker(StringRedisTemplate redis,
                                                   RedisScripts scripts,
                                                   GatewayProperties properties) {
        return new RedisCircuitBreaker(redis, scripts, properties);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry.class)
    public RedisApiKeyRegistry redisApiKeyRegistry(StringRedisTemplate redis,
                                                   GatewayProperties properties) {
        return new RedisApiKeyRegistry(redis, properties);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.SemanticCache.class)
    public RedisSemanticCache redisSemanticCache(StringRedisTemplate redis,
                                                 GatewayProperties properties) {
        return new RedisSemanticCache(redis,
                io.github.leohou0430.llmgw.governance.GovernanceLimits.from(properties));
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.routing.TaskRouteSticky.class)
    public RedisTaskRouteSticky redisTaskRouteSticky(StringRedisTemplate redis,
                                                     GatewayProperties properties) {
        return new RedisTaskRouteSticky(redis, properties.getVideos().getStickyTtlSeconds());
    }
}
