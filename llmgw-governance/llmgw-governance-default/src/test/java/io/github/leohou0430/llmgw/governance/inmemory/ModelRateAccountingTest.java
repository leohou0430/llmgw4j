package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class ModelRateAccountingTest {
    private void verifyRefund(boolean success, boolean cached) throws Exception {
        var p = new GatewayProperties();
        var key = new GatewayProperties.ApiKeyDef();key.setId("team");key.setToken("secret");
        key.setQuotaTokens(10000);p.setApiKeys(List.of(key));
        var limit = new GatewayProperties.ModelLimit();limit.setModel("m");
        limit.setTokensPerSecond(1);limit.setBurst(2000);p.getModelLimits().add(limit);
        var limiter = new AtomicTokenLimiter(p);
        var route = new Route("r","*","","http://localhost","",1,List.of());
        var parsed = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":\"" + "x".repeat(8000) + "\"}]}");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chain = new DefaultGovernanceChain(new InMemoryApiKeyRegistry(p), limiter,
                    new InMemoryUsageLedger(),new CaffeineSemanticCache(p),new InMemoryAuditSink(),
                    executor,new GovernanceMetrics(new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)),p);
            var admitted = chain.enter("Bearer secret",parsed,route);
            assertTrue(admitted.allowed());
            var context = GatewayPipeline.context(admitted);
            if (cached) context.markCached();
            context.setCompletionTokens(1);
            chain.finish(context,success);executor.submit(() -> {}).get();
            if (!success || cached) {
                assertTrue(chain.enter("Bearer secret",parsed,route).allowed(),
                        "failed/cache requests must return the model reservation");
            } else {
                limiter.refund("model:m",100000);
                assertEquals(2000,limiter.remaining("model:m"),"settlement must retain model-specific capacity");
            }
        }
    }
    @Test void failedRequestsRefundTheModelBucket() throws Exception { verifyRefund(false,false); }
    @Test void cacheHitsRefundTheModelBucket() throws Exception { verifyRefund(true,true); }
    @Test void successSettlementKeepsTheConfiguredModelShape() throws Exception { verifyRefund(true,false); }
}
