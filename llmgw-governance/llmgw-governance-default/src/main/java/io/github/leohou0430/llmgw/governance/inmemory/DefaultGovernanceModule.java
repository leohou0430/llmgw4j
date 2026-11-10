package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.model.ModelCatalog;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native constructor composition, discovered as a named module before any service is resolved. */
public final class DefaultGovernanceModule implements ExtensionModule {
    @Override public void configure(ExtensionContext c) {
        var p = c.get(GatewayProperties.class);
        p.validate();
        c.bindDefault(io.github.leohou0430.llmgw.tokenizer.TokenCounter.class, () ->
                io.github.leohou0430.llmgw.core.extension.Extensions.load(io.github.leohou0430.llmgw.tokenizer.TokenCounter.class).getExtension(p.getTokenizer()));
        c.bindDefault(ExecutorService.class, Executors::newVirtualThreadPerTaskExecutor);
        c.bindDefault(DynamicGovernanceSettings.class, () -> new DynamicGovernanceSettings(p));
        c.bindDefault(ApiKeyRegistry.class, () -> new ManagedApiKeyRegistry(p, new JsonKeyStore(p)));
        c.bindDefault(TokenLimiter.class, () -> new AtomicTokenLimiter(p, c.get(DynamicGovernanceSettings.class)));
        c.bindDefault(UsageLedger.class, InMemoryUsageLedger::new);
        c.bindDefault(BudgetLedger.class, InMemoryBudgetLedger::new);
        c.bindDefault(RequestLogSink.class, () -> new InMemoryRequestLog(p.getObservability().getRequestLogCapacity()));
        c.bindDefault(HttpGuardrail.class, () -> new HttpGuardrail(p));
        c.bindDefault(Guardrail.class, () -> new GuardrailChain(List.of(new PatternGuardrail(p), c.get(HttpGuardrail.class))));
        c.bindDefault(Embedder.class, () -> new HttpEmbedder(p));
        c.bindDefault(TokenValidator.class, () -> new JwtTokenValidator(p));
        c.bindDefault(TaskRouteSticky.class, () -> new InMemoryTaskRouteSticky(p.getVideos().getStickyMaxEntries(), p.getVideos().getStickyTtlSeconds()));
        c.bindDefault(SemanticCache.class, () -> new CaffeineSemanticCache(p, c.get(DynamicGovernanceSettings.class)));
        c.bindDefault(AuditSink.class, InMemoryAuditSink::new);
        c.bindDefault(GovernanceMetrics.class, () -> new GovernanceMetrics(c.contains(MeterRegistry.class) ? c.get(MeterRegistry.class) : null));
        c.bindDefault(SnapshotStore.class, () -> new JsonSnapshotStore(c.get(UsageLedger.class), p));
        c.bindDefault(RouteConfigSource.class, () -> new InMemoryRouteConfigSource(p));
        c.bindDefault(Router.class, () -> new ConfigRouter(c.get(RouteConfigSource.class)));
        c.bindDefault(CircuitBreaker.class, () -> new WindowedCircuitBreaker(p, c.get(GovernanceMetrics.class), c.get(DynamicGovernanceSettings.class)));
        c.bindDefault(HeaderPolicy.class, () -> new AllowListHeaderPolicy(p));
        c.bindDefault(ModelCatalog.class, () -> new ConfigModelCatalog(p));
        c.bindDefault(GovernanceChain.class, () -> new DefaultGovernanceChain(
                c.get(ApiKeyRegistry.class), c.get(TokenLimiter.class), c.get(UsageLedger.class),
                c.get(SemanticCache.class), c.get(AuditSink.class), c.get(ExecutorService.class),
                c.get(GovernanceMetrics.class), p, c.get(BudgetLedger.class), c.get(ModelCatalog.class),
                c.get(RequestLogSink.class), c.get(Guardrail.class), c.get(Embedder.class), c.get(TokenValidator.class), c.get(io.github.leohou0430.llmgw.tokenizer.TokenCounter.class)));
    }
}
