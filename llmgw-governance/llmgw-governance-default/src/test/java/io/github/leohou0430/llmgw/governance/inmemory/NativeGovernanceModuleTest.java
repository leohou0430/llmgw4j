package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeGovernanceModuleTest {
    @TempDir Path folder;
    @Test void actualDubboModuleBuildsAndClosesTheGraphWithoutSpringOnItsClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        var p = new GatewayProperties();p.setKeysStorePath(folder.resolve("keys.json").toString());
        var context = new ExtensionContext();
        context.external(GatewayProperties.class,p);context.loadModule("in-memory");context.seal();
        try {
            assertInstanceOf(DefaultGovernanceChain.class, context.get(GovernanceChain.class));
            assertInstanceOf(ManagedApiKeyRegistry.class, context.get(ApiKeyRegistry.class));
            assertSame(context.get(GovernanceChain.class),context.get(GovernanceChain.class));
            var sidecar = context.get(ExecutorService.class);
            context.close();
            assertTrue(sidecar.isShutdown());
        } finally { context.close(); }
    }
    @Test void aNativeReplacementIsUsedByTheResolvedGovernanceChain() {
        var p = new GatewayProperties();p.setKeysStorePath(folder.resolve("custom-keys.json").toString());
        var calls = new AtomicInteger();
        TokenLimiter reject = new TokenLimiter() {
            public boolean tryAcquire(String key,int tokens) { calls.incrementAndGet();return false; }
            public void refund(String key,int tokens) {}
            public long remaining(String key) { return 0; }
        };
        try (var context = new ExtensionContext()) {
            context.external(GatewayProperties.class,p);context.loadModule("in-memory");
            context.replace(TokenLimiter.class, () -> reject);context.seal();
            var parsed = ProtocolCodec.parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
            var decision = context.get(GovernanceChain.class).enter("Bearer sk-demo",parsed,new Route("r","*","","http://localhost","",1,List.of()));
            assertFalse(decision.allowed());assertEquals(1,calls.get());
        }
    }
}
