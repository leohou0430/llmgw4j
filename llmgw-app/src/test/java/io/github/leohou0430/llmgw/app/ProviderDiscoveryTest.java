package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.provider.UpstreamAuthenticator;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderDiscoveryTest {
    @Test void authenticatorsLoadWithoutStartingSpring() {
        var loader = Extensions.load(UpstreamAuthenticator.class);
        assertEquals("Bearer example", loader.getDefaultExtension().authHeaders("example").get("Authorization"));
        var anthropic = loader.getExtension("anthropic");
        assertEquals("example", anthropic.authHeaders("example").get("x-api-key"));
        assertFalse(anthropic.authHeaders("example").containsKey("Authorization"));
        assertTrue(anthropic.authHeaders("${UNRESOLVED}").isEmpty());
        assertThrows(IllegalStateException.class, () -> loader.getExtension("missing-explicit-provider"));
    }
}
