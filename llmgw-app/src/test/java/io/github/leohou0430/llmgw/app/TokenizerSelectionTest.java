package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.tokenizer.TokenCounter;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerSelectionTest {
    @TempDir Path directory;
    @Test void selectedTokenizerFeedsTheAdmissionEstimateWithoutRewritingBytes() {
        var loader = ExtensionLoader.getExtensionLoader(TokenCounter.class);
        var parsed = ProtocolCodec.parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\""+"a".repeat(1000)+"\"}]}");
        assertEquals(parsed.estimatedTokens(),loader.getExtension("heuristic").estimate(parsed));
        var p = new GatewayProperties();p.setKeysStorePath(directory.resolve("keys.json").toString());
        p.setTokenizer("jtokkit");
        try (var c = new ExtensionContext()) {
            c.external(GatewayProperties.class,p);c.loadModule("in-memory");c.seal();
            if (!loader.hasExtension("jtokkit")) {
                assertThrows(IllegalStateException.class, () -> c.get(GovernanceChain.class));
            } else {
                var decision = c.get(GovernanceChain.class).enter("Bearer sk-demo",parsed,new Route("r","*","","http://localhost","",1,List.of()));
                assertTrue(decision.allowed());assertEquals(parsed.rawJson(),decision.parsed().rawJson());
                assertEquals(loader.getExtension("jtokkit").estimate(parsed),decision.parsed().estimatedTokens());
                assertNotEquals(parsed.estimatedTokens(),decision.parsed().estimatedTokens());
            }
        }
    }
}
