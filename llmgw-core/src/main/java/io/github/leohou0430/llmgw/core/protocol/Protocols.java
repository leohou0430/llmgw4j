package io.github.leohou0430.llmgw.core.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static lookup of the protocol implementations. Defaults are discovered through Dubbo SPI for all wire
 * protocols; {@link #register(Protocol)} lets a deployment override any of
 * them explicitly in native Java. Spring scanning is not involved.
 */
public final class Protocols {

    private static final Map<ProtocolKind, Protocol> BY_KIND = new ConcurrentHashMap<>();

    static {
        loadDefaults();
    }

    private Protocols() {
    }

    public static Protocol of(ProtocolKind kind) {
        Protocol protocol = BY_KIND.get(kind);
        if (protocol == null) {
            throw new IllegalStateException("no protocol registered for " + kind);
        }
        return protocol;
    }

    /** Custom override for an existing kind (last registration wins). */
    public static void register(Protocol protocol) {
        BY_KIND.put(protocol.kind(), protocol);
    }

    private static void loadDefaults() {
        var loader = org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader(Protocol.class);
        for (ProtocolKind kind : ProtocolKind.values()) BY_KIND.put(kind, loader.getExtension(kind.wireName()));
    }

    /** Test-only: restore the named Dubbo implementations. */
    static void resetToDefaults() {
        BY_KIND.clear();
        loadDefaults();
    }
}
