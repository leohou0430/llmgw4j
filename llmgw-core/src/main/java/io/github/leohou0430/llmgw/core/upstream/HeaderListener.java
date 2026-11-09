package io.github.leohou0430.llmgw.core.upstream;

import java.util.List;
import java.util.Map;

/**
 * Callback fired once per upstream response when its headers arrive, BEFORE any
 * body line flows. This is the only place response headers can be observed:
 * the streaming path otherwise forwards SSE lines verbatim and never surfaces
 * header metadata (x-ratelimit-*, retry-after, request ids) to the caller.
 * <p>Listeners must be cheap and non-blocking: they run on the HTTP client's
 * response thread. Storing into an AtomicReference and applying later is the
 * intended pattern.
 */
@FunctionalInterface
public interface HeaderListener {

    void onHeaders(int status, Map<String, List<String>> headers);

    static HeaderListener none() {
        return (status, headers) -> {
        };
    }
}
