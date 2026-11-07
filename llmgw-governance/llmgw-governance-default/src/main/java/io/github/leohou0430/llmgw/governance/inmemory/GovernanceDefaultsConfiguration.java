package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.core.governance.SnapshotStore;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.core.routing.ConfigRouter;
import io.github.leohou0430.llmgw.core.routing.InMemoryRouteConfigSource;
import io.github.leohou0430.llmgw.core.routing.RouteConfigSource;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The default implementation pack, registered as a Spring Boot auto-configuration.
 *
 * <p>Every bean is guarded by {@code @ConditionalOnMissingBean} on the SPI type,
 * so a deployment only needs to declare its own bean (any {@code @Component}
 * {@code @Bean} in the application's own configuration) to replace the default:
 *
 * <pre>
 * // e.g. swap Redis-backed quota without touching the gateway code
 * &#64;Component
 * public class RedisTokenLimiter implements TokenLimiter { ... }
 * </pre>
 *
 * <p>The default bean for that SPI silently backs off; everything else still
 * wires. The engines and core never reference any class in this module --
 * they only see the SPI interfaces from {@code llmgw-core}.
 */
@AutoConfiguration
public class GovernanceDefaultsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExecutorService.class)
    public ExecutorService governanceSideCar() {
        // Blocking governance work (ledger writes, audit, cache fills) never
        // runs on the event loop; it is dispatched to this per-task pool.
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRegistry.class)
    public InMemoryApiKeyRegistry inMemoryApiKeyRegistry(GatewayProperties properties) {
        return new InMemoryApiKeyRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean(TokenLimiter.class)
    public AtomicTokenLimiter atomicTokenLimiter(GatewayProperties properties) {
        return new AtomicTokenLimiter(properties);
    }

    @Bean
    @ConditionalOnMissingBean(UsageLedger.class)
    public InMemoryUsageLedger inMemoryUsageLedger() {
        return new InMemoryUsageLedger();
    }

    @Bean
    @ConditionalOnMissingBean(SemanticCache.class)
    public CaffeineSemanticCache caffeineSemanticCache(GatewayProperties properties) {
        return new CaffeineSemanticCache(properties);
    }

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    public InMemoryAuditSink inMemoryAuditSink() {
        return new InMemoryAuditSink();
    }

    @Bean
    @ConditionalOnMissingBean(GovernanceMetrics.class)
    public GovernanceMetrics governanceMetrics(ObjectProvider<MeterRegistry> meterRegistries) {
        return new GovernanceMetrics(meterRegistries);
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotStore.class)
    public JsonSnapshotStore jsonSnapshotStore(UsageLedger ledger, GatewayProperties properties) {
        return new JsonSnapshotStore(ledger, properties);
    }

    @Bean
    @ConditionalOnMissingBean(RouteConfigSource.class)
    public InMemoryRouteConfigSource routeConfigSource(GatewayProperties properties) {
        return new InMemoryRouteConfigSource(properties);
    }

    @Bean
    @ConditionalOnMissingBean(Router.class)
    public ConfigRouter configRouter(RouteConfigSource source) {
        return new ConfigRouter(source);
    }

    @Bean
    @ConditionalOnMissingBean(CircuitBreaker.class)
    public WindowedCircuitBreaker windowedCircuitBreaker(GatewayProperties properties,
                                                         GovernanceMetrics metrics) {
        return new WindowedCircuitBreaker(properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(GovernanceChain.class)
    public DefaultGovernanceChain defaultGovernanceChain(ApiKeyRegistry apiKeys,
                                                         TokenLimiter limiter,
                                                         UsageLedger ledger,
                                                         SemanticCache cache,
                                                         AuditSink audit,
                                                         ExecutorService sideCar,
                                                         GovernanceMetrics metrics,
                                                         GatewayProperties properties) {
        return new DefaultGovernanceChain(apiKeys, limiter, ledger, cache, audit,
                sideCar, metrics, properties);
    }
}
