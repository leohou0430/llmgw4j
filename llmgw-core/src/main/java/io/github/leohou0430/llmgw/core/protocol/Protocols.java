package io.github.leohou0430.llmgw.core.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static lookup of the protocol implementations. Defaults cover the five wire
 * protocols; {@link #register(Protocol)} lets a deployment override any of
 * them (or wire protocol beans via {@code ProtocolRegistrar}, which the
 * governance default pack registers as an auto-configuration).
 */
public final class Protocols {

    private static final Map<ProtocolKind, Protocol> BY_KIND = new ConcurrentHashMap<>();

    static {
        DefaultProtocols.registerAll(BY_KIND);
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

    /** Test-only: restore the five default implementations. */
    static void resetToDefaults() {
        BY_KIND.clear();
        DefaultProtocols.registerAll(BY_KIND);
    }
}
