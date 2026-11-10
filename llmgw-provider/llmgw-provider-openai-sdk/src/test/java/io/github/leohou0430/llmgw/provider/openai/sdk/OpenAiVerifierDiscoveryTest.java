package io.github.leohou0430.llmgw.provider.openai.sdk;
import io.github.leohou0430.llmgw.provider.ProviderVerifier;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OpenAiVerifierDiscoveryTest {
    @Test void sdkValidationIsAStatelessNamedExtensionWithoutSpring() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        var verifier = Extensions.load(ProviderVerifier.class).getDefaultExtension();
        assertTrue(verifier.validate("example-model","hello"));
        assertTrue(verifier.verify(new GatewayProperties()).startsWith("skipped:"));
    }
}
