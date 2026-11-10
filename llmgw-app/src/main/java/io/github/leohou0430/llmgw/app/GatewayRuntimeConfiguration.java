package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.model.ModelCatalog;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.governance.inmemory.DynamicGovernanceSettings;
import io.github.leohou0430.llmgw.governance.inmemory.GovernanceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.concurrent.ExecutorService;

/** Exposes an already composed native graph to the HTTP application, without implementation scanning. */
@Configuration(proxyBeanMethods = false)
public class GatewayRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    public ExtensionContext gatewayRuntime(GatewayProperties properties, ObjectProvider<MeterRegistry> registries, Environment environment) {
        legacyRedisProperties(properties, environment);
        var context = new ExtensionContext();
        try {
            context.external(GatewayProperties.class, properties);
            MeterRegistry registry = registries.getIfAvailable();
            if (registry != null) context.external(MeterRegistry.class, registry);
            context.loadModule("in-memory");
            if (properties.getRedis().isEnabled()) context.loadModule("redis");
            context.loadModule("jdk-http");
            for (String module : properties.getExtensionModules()) context.loadModule(module);
            context.seal();
            if (properties.getGovernance().isSnapshotAutoRestore()) context.get(SnapshotStore.class).load();
            return context;
        } catch (RuntimeException | Error failure) {
            try { context.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** Compatibility only at the Boot edge; native modules know gateway.redis.* exclusively. */
    private static void legacyRedisProperties(GatewayProperties p, Environment env) {
        var r = p.getRedis();
        if (r.isEnabled() && env.containsProperty("spring.data.redis.url")) {
            throw new IllegalArgumentException("migrate spring.data.redis.url to gateway.redis host/port/username/password/database/ssl");
        }
        if (!env.containsProperty("gateway.redis.host")) r.setHost(env.getProperty("spring.data.redis.host", r.getHost()));
        if (!env.containsProperty("gateway.redis.port")) r.setPort(env.getProperty("spring.data.redis.port", Integer.class, r.getPort()));
        if (!env.containsProperty("gateway.redis.database")) r.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, r.getDatabase()));
        if (!env.containsProperty("gateway.redis.username")) r.setUsername(env.getProperty("spring.data.redis.username", r.getUsername()));
        if (!env.containsProperty("gateway.redis.password")) r.setPassword(env.getProperty("spring.data.redis.password", r.getPassword()));
        if (!env.containsProperty("gateway.redis.ssl")) r.setSsl(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, r.isSsl()));
        if (!env.containsProperty("gateway.redis.timeout-ms") && env.containsProperty("spring.data.redis.timeout")) {
            r.setTimeoutMs(Math.toIntExact(org.springframework.boot.convert.DurationStyle.detectAndParse(env.getProperty("spring.data.redis.timeout")).toMillis()));
        }
    }
    @Bean(destroyMethod = "") public io.github.leohou0430.llmgw.provider.UpstreamProvider upstreamProvider(ExtensionContext c) { return c.get(io.github.leohou0430.llmgw.provider.UpstreamProvider.class); }
    @Bean(destroyMethod = "") public ApiKeyRegistry apiKeyRegistry(ExtensionContext c) { return c.get(ApiKeyRegistry.class); }
    @Bean(destroyMethod = "") public TokenLimiter tokenLimiter(ExtensionContext c) { return c.get(TokenLimiter.class); }
    @Bean(destroyMethod = "") public UsageLedger usageLedger(ExtensionContext c) { return c.get(UsageLedger.class); }
    @Bean(destroyMethod = "") public BudgetLedger budgetLedger(ExtensionContext c) { return c.get(BudgetLedger.class); }
    @Bean(destroyMethod = "") public RequestLogSink requestLogSink(ExtensionContext c) { return c.get(RequestLogSink.class); }
    @Bean(destroyMethod = "") public Guardrail guardrail(ExtensionContext c) { return c.get(Guardrail.class); }
    @Bean(destroyMethod = "") public Embedder embedder(ExtensionContext c) { return c.get(Embedder.class); }
    @Bean(destroyMethod = "") public TokenValidator tokenValidator(ExtensionContext c) { return c.get(TokenValidator.class); }
    @Bean(destroyMethod = "") public TaskRouteSticky taskRouteSticky(ExtensionContext c) { return c.get(TaskRouteSticky.class); }
    @Bean(destroyMethod = "") public SemanticCache semanticCache(ExtensionContext c) { return c.get(SemanticCache.class); }
    @Bean(destroyMethod = "") public AuditSink auditSink(ExtensionContext c) { return c.get(AuditSink.class); }
    @Bean(destroyMethod = "") public GovernanceMetrics governanceMetrics(ExtensionContext c) { return c.get(GovernanceMetrics.class); }
    @Bean(destroyMethod = "") public SnapshotStore snapshotStore(ExtensionContext c) { return c.get(SnapshotStore.class); }
    @Bean(destroyMethod = "") public RouteConfigSource routeConfigSource(ExtensionContext c) { return c.get(RouteConfigSource.class); }
    @Bean(destroyMethod = "") public Router router(ExtensionContext c) { return c.get(Router.class); }
    @Bean(destroyMethod = "") public CircuitBreaker circuitBreaker(ExtensionContext c) { return c.get(CircuitBreaker.class); }
    @Bean(destroyMethod = "") public HeaderPolicy headerPolicy(ExtensionContext c) { return c.get(HeaderPolicy.class); }
    @Bean(destroyMethod = "") public ModelCatalog modelCatalog(ExtensionContext c) { return c.get(ModelCatalog.class); }
    @Bean(destroyMethod = "") public GovernanceChain governanceChain(ExtensionContext c) { return c.get(GovernanceChain.class); }
    @Bean(destroyMethod = "") public ExecutorService executorService(ExtensionContext c) { return c.get(ExecutorService.class); }
    @Bean(destroyMethod = "") public DynamicGovernanceSettings dynamicGovernanceSettings(ExtensionContext c) { return c.get(DynamicGovernanceSettings.class); }
}
