package io.github.leohou0430.llmgw.engine;

/** Wire-level header and media-type names shared by every data plane. */
public final class EngineHeaders {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String OVERHEAD_MS = "X-LLMGW-Overhead-Ms";
    public static final String UPSTREAM_MS = "X-LLMGW-Upstream-Ms";

    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String NO_CACHE = "no-cache";
    public static final String NO_STORE = "no-store";

    public static final String APPLICATION_JSON = "application/json";
    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    private EngineHeaders() {
    }
}
