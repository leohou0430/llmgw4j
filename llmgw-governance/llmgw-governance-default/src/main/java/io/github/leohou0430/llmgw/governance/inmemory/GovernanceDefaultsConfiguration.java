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

import java.util.List;
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
    @ConditionalOnMissingBean(DynamicGovernanceSettings.class)
    public DynamicGovernanceSettings dynamicGovernanceSettings(GatewayProperties properties) {
        return new DynamicGovernanceSettings(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRegistry.class)
    public ManagedApiKeyRegistry managedApiKeyRegistry(GatewayProperties properties) {
        // v13: supersedes the bare in-memory registry -- same read path, plus
        // KeyAdminService (create/revoke/TTL) backed by a JSON key store.
        return new ManagedApiKeyRegistry(properties,
                new JsonKeyStore(properties));
    }

    @Bean
    @ConditionalOnMissingBean(TokenLimiter.class)
    public AtomicTokenLimiter atomicTokenLimiter(GatewayProperties properties,
                                                 DynamicGovernanceSettings settings) {
        return new AtomicTokenLimiter(properties, settings);
    }

    @Bean
    @ConditionalOnMissingBean(UsageLedger.class)
    public InMemoryUsageLedger inMemoryUsageLedger() {
        return new InMemoryUsageLedger();
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.BudgetLedger.class)
    public InMemoryBudgetLedger inMemoryBudgetLedger() {
        return new InMemoryBudgetLedger();
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.RequestLogSink.class)
    public InMemoryRequestLog inMemoryRequestLog(GatewayProperties properties) {
        return new InMemoryRequestLog(properties.getObservability().getRequestLogCapacity());
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.Guardrail.class)
    public GuardrailChain guardrailChain(GatewayProperties properties) {
        // W4.1: pattern-deny (local, cheap) first, then the external engine.
        return new GuardrailChain(List.of(
                new PatternGuardrail(properties),
                new HttpGuardrail(properties)));
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.Embedder.class)
    public HttpEmbedder httpEmbedder(GatewayProperties properties) {
        return new HttpEmbedder(properties);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.governance.TokenValidator.class)
    public JwtTokenValidator jwtTokenValidator(GatewayProperties properties) {
        return new JwtTokenValidator(properties);
    }

    /**
     * W3.6/W4.3: task-route stickiness (video/batch/file jobs) as an SPI — the
     * Redis pack swaps in a cross-replica store; the default stays in-memory.
     */
    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.routing.TaskRouteSticky.class)
    public io.github.leohou0430.llmgw.core.routing.TaskRouteSticky inMemoryTaskRouteSticky(
            GatewayProperties properties) {
        return new io.github.leohou0430.llmgw.core.routing.InMemoryTaskRouteSticky(
                properties.getVideos().getStickyMaxEntries(),
                properties.getVideos().getStickyTtlSeconds());
    }

    @Bean
    @ConditionalOnMissingBean(SemanticCache.class)
    public CaffeineSemanticCache caffeineSemanticCache(GatewayProperties properties,
                                                       DynamicGovernanceSettings settings) {
        return new CaffeineSemanticCache(properties, settings);
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

    /**
     * v13 opt-in startup restore: reload the usage ledger from the snapshot
     * file written by POST /admin/snapshot/save. Off by default so benchmark
     * arms always start from a fresh process (see JsonSnapshotStore javadoc).
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "gateway.governance", name = "snapshot-auto-restore", havingValue = "true")
    public org.springframework.boot.ApplicationRunner snapshotAutoRestore(SnapshotStore snapshots) {
        return args -> snapshots.load();
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
                                                         GovernanceMetrics metrics,
                                                         DynamicGovernanceSettings settings) {
        return new WindowedCircuitBreaker(properties, metrics, settings);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.upstream.HeaderPolicy.class)
    public AllowListHeaderPolicy allowListHeaderPolicy(GatewayProperties properties) {
        return new AllowListHeaderPolicy(properties);
    }

    /**
     * v13 Protocol SPI: user-declared {@link Protocol} beans override the
     * default behavior for their kind at startup.
     */
    @Bean
    public io.github.leohou0430.llmgw.core.protocol.ProtocolRegistrar protocolRegistrar(
            org.springframework.beans.factory.ObjectProvider<io.github.leohou0430.llmgw.core.protocol.Protocol> protocols) {
        return new io.github.leohou0430.llmgw.core.protocol.ProtocolRegistrar(protocols);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.leohou0430.llmgw.core.model.ModelCatalog.class)
    public ConfigModelCatalog configModelCatalog(GatewayProperties properties) {
        return new ConfigModelCatalog(properties);
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
                                                         GatewayProperties properties,
                                                         io.github.leohou0430.llmgw.core.governance.BudgetLedger budgets,
                                                         io.github.leohou0430.llmgw.core.model.ModelCatalog catalog,
                                                         io.github.leohou0430.llmgw.core.governance.RequestLogSink requestLog,
                                                         io.github.leohou0430.llmgw.core.governance.Guardrail guardrail,
                                                         io.github.leohou0430.llmgw.core.governance.Embedder embedder,
                                                         io.github.leohou0430.llmgw.core.governance.TokenValidator tokenValidator) {
        return new DefaultGovernanceChain(apiKeys, limiter, ledger, cache, audit,
                sideCar, metrics, properties, budgets, catalog, requestLog, guardrail,
                embedder, tokenValidator);
    }
}
