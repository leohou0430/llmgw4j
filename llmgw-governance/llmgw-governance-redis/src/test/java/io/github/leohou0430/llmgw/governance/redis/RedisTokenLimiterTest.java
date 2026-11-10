package io.github.leohou0430.llmgw.governance.redis;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LLMGW_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisTokenLimiterTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final String prefix = "llmgw-test:" + UUID.randomUUID() + ":";
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("LLMGW_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }
    @AfterEach void removeOwnKeys() {
        var keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @AfterAll static void disconnect() { factory.destroy(); }
    @Test void modelSpecificCapacityAppliesAcrossReplicas() {
        var a = new RedisTokenLimiter(redis, new RedisScripts(), prefix, 100000, 100000);
        var b = new RedisTokenLimiter(redis, new RedisScripts(), prefix, 100000, 100000);
        assertTrue(a.tryAcquire("model:m", 2, 1, 2));
        assertFalse(b.tryAcquire("model:m", 2, 1, 2), "model must not inherit the global capacity");
    }
    @Test void modelRefundCannotExpandItsCapacityToTheGlobalBurst() {
        var limiter = new RedisTokenLimiter(redis, new RedisScripts(), prefix, 100000, 100000);
        assertTrue(limiter.tryAcquire("model:m", 2, 1, 2));
        limiter.refund("model:m", 1000);
        assertEquals(2, limiter.remaining("model:m"));
    }
}
