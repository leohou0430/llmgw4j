package io.github.leohou0430.llmgw.core.protocol;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolDiscoveryTest {
    @Test void everyWireProtocolIsAnActualNamedDubboExtension() {
        var loader = ExtensionLoader.getExtensionLoader(Protocol.class);
        for (var kind : ProtocolKind.values()) {
            assertEquals(kind, loader.getExtension(kind.wireName()).kind());
        }
        assertThrows(IllegalStateException.class, () -> loader.getExtension("not-configured"));
    }
}
