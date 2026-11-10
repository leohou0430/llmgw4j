package io.github.leohou0430.llmgw.app;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.DefaultProtocols;
import io.github.leohou0430.llmgw.governance.inmemory.DefaultGovernanceModule;
import io.github.leohou0430.llmgw.provider.http.jdk.JdkHttpModule;
import io.github.leohou0430.llmgw.provider.openai.OpenAiAuthenticator;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionRuntimeHintsTest {
    @Test void namedExtensionsAndPlainConfigurationAreIncludedInAotHints() {
        var hints = new RuntimeHints();
        new GatewayRuntimeHints.Registrar().registerHints(hints,getClass().getClassLoader());
        for (var type : new Class<?>[]{DefaultProtocols.Chat.class,DefaultGovernanceModule.class,JdkHttpModule.class,OpenAiAuthenticator.class,GatewayProperties.Redis.class}) {
            assertNotNull(hints.reflection().getTypeHint(type),type.getName());
        }
        assertNull(hints.reflection().getTypeHint(
                org.springframework.aot.hint.TypeReference.of("org.apache.dubbo.common.json.impl.FastJson2Impl")));
    }
}
