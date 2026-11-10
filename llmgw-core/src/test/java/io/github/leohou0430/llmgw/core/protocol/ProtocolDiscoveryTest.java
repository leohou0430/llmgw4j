package io.github.leohou0430.llmgw.core.protocol;

import io.github.leohou0430.llmgw.core.extension.Extensions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolDiscoveryTest {
    @Test void everyWireProtocolIsANamedProjectExtension() {
        var loader = Extensions.load(Protocol.class);
        for (var kind : ProtocolKind.values()) {
            assertEquals(kind, loader.getExtension(kind.wireName()).kind());
        }
        assertThrows(IllegalStateException.class, () -> loader.getExtension("not-configured"));
    }
}
