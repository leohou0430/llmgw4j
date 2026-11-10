package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class GovernanceSafetyTest {
    private GatewayProperties properties(boolean production, boolean priced) {
        var p = new GatewayProperties();p.setProduction(production);p.setKeysStorePath("");
        var key = new GatewayProperties.ApiKeyDef();key.setId("team");key.setToken("secret");
        key.setBudgetMicroUsd(100);key.setQuotaTokens(10000);p.setApiKeys(List.of(key));
        if (priced) {
            var price = new GatewayProperties.ModelDef();price.setModel("m");
            price.setInputPriceMicroUsdPerMillion(1000000);price.setOutputPriceMicroUsdPerMillion(1000000);
            p.getModels().add(price);
        }
        return p;
    }
    @Test void jwtOnlyProductionNeverSeedsDemoCredentials() {
        var p = new GatewayProperties();p.setProduction(true);p.getAuth().setJwtSecret("jwt-secret");
        assertTrue(new InMemoryApiKeyRegistry(p).find("sk-demo").isEmpty());
    }
    @Test void unknownUpstreamOutcomeKeepsMoneyReserved() throws Exception {
        var p = properties(false, true);
        var budgets = new InMemoryBudgetLedger();
        assertTrue(budgets.tryReserve("team", 10, 100));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chain = new DefaultGovernanceChain(new ManagedApiKeyRegistry(p,new JsonKeyStore(p)),
                    new AtomicTokenLimiter(p), new InMemoryUsageLedger(), new CaffeineSemanticCache(p),
                    new InMemoryAuditSink(), executor,
                    new GovernanceMetrics(new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)),
                    p, budgets, new ConfigModelCatalog(p));
            var context = new GatewayContext("team", "m", "r", false, 1);
            context.setBudgetReservedMicroUsd(10);context.markUpstreamStart();
            chain.finish(context, false);executor.submit(() -> {}).get();
            assertFalse(budgets.tryReserve("team",100,100), "unknown outcome must not refund money");
        }
    }

    @Test void runtimeMoneyBudgetCannotBypassProductionStartupRestriction() throws Exception {
        var p = properties(true, true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chain = new DefaultGovernanceChain(new ManagedApiKeyRegistry(p, new JsonKeyStore(p)),
                    new AtomicTokenLimiter(p),new InMemoryUsageLedger(),new CaffeineSemanticCache(p),
                    new InMemoryAuditSink(), executor,new GovernanceMetrics(new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)),p,
                    new InMemoryBudgetLedger(),new ConfigModelCatalog(p));
            var decision = chain.enter("Bearer secret", ProtocolCodec.parse(ProtocolKind.CHAT,
                    "{\"model\":\"m\",\"messages\":[{\"content\":\"hello\"}]}"),
                    new Route("r", "*", "", "http://localhost", "", 1, List.of()));
            assertFalse(decision.allowed());assertEquals(503, decision.status());
        }
    }
    @Test void unknownPriceCannotMakeBudgetedRequestFree() throws Exception {
        var p = properties(false, false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chain = new DefaultGovernanceChain(new ManagedApiKeyRegistry(p, new JsonKeyStore(p)),
                    new AtomicTokenLimiter(p),new InMemoryUsageLedger(),new CaffeineSemanticCache(p),
                    new InMemoryAuditSink(), executor,new GovernanceMetrics(new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)),p,
                    new InMemoryBudgetLedger(),new ConfigModelCatalog(p));
            var decision = chain.enter("Bearer secret", ProtocolCodec.parse(ProtocolKind.CHAT,
                    "{\"model\":\"unknown\",\"messages\":[{\"content\":\"hello\"}]}"),
                    new Route("r", "*", "", "http://localhost", "", 1, List.of()));
            assertFalse(decision.allowed());assertEquals(503, decision.status());
        }
    }
}
