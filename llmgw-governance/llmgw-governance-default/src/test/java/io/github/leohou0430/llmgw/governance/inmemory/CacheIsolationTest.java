package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class CacheIsolationTest {
    private static final String CHAT = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"private prompt\"}]}";
    private static final String RESPONSE = "{\"object\":\"chat.completion\",\"choices\":[{\"message\":{\"content\":\"private answer\"}}]}";

    private void assertMiss(String tenant, ProtocolKind kind, String body) throws Exception {
        var properties = new GatewayProperties();
        properties.getCache().setSemanticThreshold(0.85);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var metrics = new GovernanceMetrics(null) {
                @Override public void count(String name, String... tags) { }
            };
            var chain = new DefaultGovernanceChain(null, null, null,
                    new CaffeineSemanticCache(properties), null, executor, metrics, properties,
                    null, null, null, null, text -> new double[]{1, 0});
            chain.cache("a", ProtocolCodec.parse(ProtocolKind.CHAT, CHAT), RESPONSE, true);
            executor.submit(() -> {}).get();
            assertTrue(chain.lookupCache(tenant, ProtocolCodec.parse(kind, body)).isEmpty());
            assertEquals(RESPONSE, chain.lookupCache("a", ProtocolCodec.parse(ProtocolKind.CHAT, CHAT)).orElseThrow());
        }
    }

    @Test void isolatesTenants() throws Exception { assertMiss("b", ProtocolKind.CHAT, CHAT); }
    @Test void isolatesModels() throws Exception { assertMiss("a", ProtocolKind.CHAT, CHAT.replace("\"m\"", "\"other\"")); }
    @Test void isolatesProtocols() throws Exception { assertMiss("a", ProtocolKind.RESPONSES, "{\"model\":\"m\",\"input\":\"private prompt\"}"); }
    @Test void isolatesSamplingParameters() throws Exception { assertMiss("a", ProtocolKind.CHAT, CHAT.replace("{\"model\"", "{\"temperature\":0.8,\"model\"")); }
    @Test void isolatesSystemInstructions() throws Exception { assertMiss("a", ProtocolKind.CHAT, CHAT.replace("\"messages\":[", "\"messages\":[{\"role\":\"system\",\"content\":\"other policy\"},")); }
}
