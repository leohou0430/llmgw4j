package io.github.leohou0430.llmgw.governance.redis;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.JedisPooled;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LLMGW_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisTokenLimiterTest {
    private static JedisPooled redis;
    private final String prefix = "llmgw-test:" + UUID.randomUUID() + ":";
    @BeforeAll static void connect() {
        redis = new JedisPooled("127.0.0.1", Integer.parseInt(System.getenv("LLMGW_TEST_REDIS_PORT")));
    }
    @AfterEach void removeOwnKeys() {
        var keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.del(keys.toArray(String[]::new));
    }
    @AfterAll static void disconnect() { redis.close(); }
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

    @Test void quotaReservationAndFailureRefundRemainAtomicAcrossClientInstances() {
        var a = new RedisUsageLedger(redis,new RedisScripts(),prefix);
        var b = new RedisUsageLedger(redis,new RedisScripts(),prefix);
        assertTrue(a.tryReserve("team",7,10));
        assertFalse(b.tryReserve("team",4,10));
        a.record(new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageRecord("team","m",0,0,1,false,java.time.Instant.now(),7));
        assertTrue(b.tryReserve("team",10,10));
        b.record(new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageRecord("team","m",2,3,1,true,java.time.Instant.now(),10));
        assertEquals(5,a.used("team"));assertEquals(2,a.snapshot().get("team").requests());
        assertEquals(1,a.snapshot().get("team").errors());
    }
    @Test void nativeCacheInvalidationPreservesOtherResourceNamespaces() {
        var cache = new RedisSemanticCache(redis,prefix,60);
        var values = new java.util.ArrayList<String>();
        for(int i=0;i<1200;i++) { values.add(prefix+"cache:"+i);values.add("value"); }
        redis.mset(values.toArray(String[]::new));redis.set(prefix+"sticky:keep","route");
        cache.invalidateAll();
        assertTrue(redis.keys(prefix+"cache:*").isEmpty());
        assertEquals("route",redis.get(prefix+"sticky:keep"));
        cache.put("again","response");assertEquals("response",cache.get("again").orElseThrow());
        assertTrue(redis.ttl(prefix+"cache:again")>0);
    }
    @Test void taskBindingsKeepTenantAndProtocolIsolationWithTheNativeClient() {
        var a = new RedisTaskRouteSticky(redis,prefix,60);
        var b = new RedisTaskRouteSticky(redis,prefix,60);
        a.bind("tenant-a","files","same-id","route-a");
        assertEquals("route-a",b.resolve("tenant-a","files","same-id"));
        assertNull(b.resolve("tenant-b","files","same-id"));
        assertNull(b.resolve("tenant-a","batches","same-id"));
    }
    @Test void nativeRedisModuleLoadsWithoutSpringAndOwnsItsConnectionPool() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.data.redis.core.StringRedisTemplate"));
        var p = new io.github.leohou0430.llmgw.core.config.GatewayProperties();
        p.getRedis().setPort(Integer.parseInt(System.getenv("LLMGW_TEST_REDIS_PORT")));
        var token = "native-test-"+java.util.UUID.randomUUID();
        var key = new io.github.leohou0430.llmgw.core.config.GatewayProperties.ApiKeyDef();
        key.setId("native-test");key.setToken(token);p.setApiKeys(java.util.List.of(key));
        var c = new io.github.leohou0430.llmgw.core.extension.ExtensionContext();
        try {
            c.external(io.github.leohou0430.llmgw.core.config.GatewayProperties.class,p);
            c.bind(io.github.leohou0430.llmgw.core.governance.TokenLimiter.class, () -> null);
            c.bind(io.github.leohou0430.llmgw.core.governance.UsageLedger.class, () -> null);
            c.bind(io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry.class, () -> null);
            c.bind(io.github.leohou0430.llmgw.core.governance.SemanticCache.class, () -> null);
            c.bind(io.github.leohou0430.llmgw.core.routing.TaskRouteSticky.class, () -> null);
            c.bind(io.github.leohou0430.llmgw.core.upstream.CircuitBreaker.class, () -> null);
            c.loadModule("redis");c.seal();
            assertInstanceOf(RedisTokenLimiter.class,c.get(io.github.leohou0430.llmgw.core.governance.TokenLimiter.class));
            assertInstanceOf(RedisUsageLedger.class,c.get(io.github.leohou0430.llmgw.core.governance.UsageLedger.class));
            assertTrue(c.get(io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry.class).find(token).isPresent());
            assertInstanceOf(RedisSemanticCache.class,c.get(io.github.leohou0430.llmgw.core.governance.SemanticCache.class));
            assertInstanceOf(RedisTaskRouteSticky.class,c.get(io.github.leohou0430.llmgw.core.routing.TaskRouteSticky.class));
            assertInstanceOf(RedisCircuitBreaker.class,c.get(io.github.leohou0430.llmgw.core.upstream.CircuitBreaker.class));
            var pool = c.get(JedisPooled.class);assertEquals("PONG",pool.ping());
            c.close();assertThrows(RuntimeException.class,pool::ping);
            assertEquals("PONG",redis.ping());
        } finally { c.close();redis.hdel("llmgw:keys",token); }
    }
}
