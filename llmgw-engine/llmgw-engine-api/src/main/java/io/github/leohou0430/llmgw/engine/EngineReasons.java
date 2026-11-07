package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;

/**
 * Single source of truth for client-visible error reasons. Both data planes
 * must emit byte-identical reason strings, otherwise the A/B comparison and
 * the 67-assertion contract suite would be comparing different protocols.
 */
public final class EngineReasons {

    public static final String REQUEST_TOO_LARGE = "request body too large";
    public static final String NO_UPSTREAM_ROUTE = "no upstream route";

    private static final String UPSTREAM_PREFIX = "upstream: ";

    private EngineReasons() {
    }

    public static String upstream(Throwable error) {
        return UPSTREAM_PREFIX + GatewayPipeline.errorMessage(error);
    }

    /** Clean EOF without the protocol terminator is a truncation, never a success. */
    public static String truncated() {
        return truncated(io.github.leohou0430.llmgw.core.protocol.ProtocolKind.CHAT);
    }

    public static String truncated(io.github.leohou0430.llmgw.core.protocol.ProtocolKind kind) {
        return UPSTREAM_PREFIX + ProtocolCodec.truncatedMessage(kind);
    }
}
