package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.governance.GatewayContext;

/**
 * Response-header timing values shared by both data planes.
 *
 * <p>Streaming responses can only report preflight overhead: headers are
 * committed before the first chunk (WebFlux subscribes to the body only after
 * {@code writeWith}; the servlet engine deliberately avoids an early
 * {@code flushBuffer}). Full TTFT and upstream timings live in the
 * {@code llmgw.ttft} / {@code llmgw.upstream} meters and the audit record.
 */
public final class EngineTimings {

    private EngineTimings() {
    }

    /** Work done before the streaming response is committed. */
    public static String preflight(GatewayContext context) {
        return GatewayContext.formatMillis(context.preflightNanos());
    }

    /** Full gateway overhead, usable once a non-streaming body is ready. */
    public static String total(GatewayContext context) {
        return GatewayContext.formatMillis(context.gatewayOverheadNanos());
    }

    /** Null when no upstream attempt was made (for example, a cache hit). */
    public static String upstreamOrNull(GatewayContext context) {
        long nanos = context.upstreamNanos();
        return nanos >= 0 ? GatewayContext.formatMillis(nanos) : null;
    }
}
