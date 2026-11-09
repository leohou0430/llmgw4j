package io.github.leohou0430.llmgw.core.governance;

import java.time.Instant;
import java.util.List;

/**
 * Request-level logging SPI (v13), separate from the audit trail: audit is a
 * compliance ring of lifecycle events, the request log is the debugging /
 * analytics surface (one entry per served or denied request, bounded, sampled
 * by capacity). Swap in a ClickHouse/Loki writer without touching the engines.
 */
public interface RequestLogSink {

    record RequestLog(
            Instant at,
            String requestId,
            String keyId,
            String model,
            String routeId,
            String protocol,
            boolean stream,
            boolean cached,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            long ttftMs,
            boolean success,
            long spendMicroUsd,
            String denyReason) {
    }

    void log(RequestLog entry);

    /** Newest first, up to {@code limit} entries. */
    List<RequestLog> recent(int limit);

    int size();
}
