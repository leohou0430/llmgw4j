package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Route resolution shared by both data planes' blocking fallback loops. */
public final class EngineRoutes {

    private EngineRoutes() {
    }

    /** Primary first, then configured fallbacks, preserving declaration order. */
    public static List<Route> candidates(Router router, Route primary) {
        List<Route> candidates = new ArrayList<>();
        candidates.add(primary);
        candidates.addAll(router.fallbacks(primary));
        return candidates;
    }

    /**
     * Build the upstream request for a route. {@code forRoute} applies the
     * optional target-model rewrite as a tree rewrite so vendor extension
     * fields survive byte-for-byte. The upstream path comes from the request's
     * protocol kind: pass-through parity means /v1/messages forwards to the
     * upstream's /messages, never to /chat/completions.
     */
    public static UpstreamRequest request(String rawJson, ParsedRequest parsed,
                                          Route route, int timeoutMs) {
        return request(rawJson, parsed, route, timeoutMs, Map.of());
    }

    /** v13: same, carrying the HeaderPolicy-filtered client headers. */
    public static UpstreamRequest request(String rawJson, ParsedRequest parsed,
                                          Route route, int timeoutMs,
                                          Map<String, String> passHeaders) {
        return new UpstreamRequest(rawJson, route.targetUrl(), route.apiKey(), route.provider(),
                parsed.model(), parsed.stream(), timeoutMs, parsed.kind().upstreamPath(),
                UpstreamMethod.POST, passHeaders).forRoute(route);
    }

    /** Bodyless GET (video poll/content) pinned to one route; no fallback. */
    public static UpstreamRequest fetch(Route route, String path, int timeoutMs) {
        return new UpstreamRequest("", route.targetUrl(), route.apiKey(), "", false,
                timeoutMs, path, UpstreamMethod.GET);
    }

    /**
     * W3.5/W4.3: POST with a JSON body whose response is BINARY (audio/speech).
     * Goes through the byte[] fetch path so the payload never rounds-trips a
     * String (which would corrupt non-UTF-8 bytes). Governance still applies.
     */
    public static UpstreamRequest postBytes(String rawJson, ParsedRequest parsed,
                                            Route route, int timeoutMs,
                                            Map<String, String> passHeaders) {
        return new UpstreamRequest(rawJson, route.targetUrl(), route.apiKey(), route.provider(),
                parsed.model(), false, timeoutMs, parsed.kind().upstreamPath(),
                UpstreamMethod.POST, passHeaders).forRoute(route);
    }

    /**
     * W4.3: POST with a BINARY body (multipart uploads — transcriptions,
     * files). Byte-faithful both ways: no charset round-trip, no model rewrite.
     */
    public static UpstreamRequest postRawBytes(byte[] body, ParsedRequest parsed,
                                               Route route, int timeoutMs,
                                               Map<String, String> passHeaders) {
        return new UpstreamRequest("", route.targetUrl(), route.apiKey(), route.provider(),
                parsed.model(), false, timeoutMs, parsed.kind().upstreamPath(),
                UpstreamMethod.POST, passHeaders,
                body == null ? new byte[0] : body).forRoute(route);
    }

    /** W4.3: bodyless GET/DELETE by explicit verb (file list/retrieve/delete). */
    public static UpstreamRequest methodFetch(Route route, String path, UpstreamMethod method,
                                              int timeoutMs) {
        return new UpstreamRequest("", route.targetUrl(), route.apiKey(), route.provider(),
                "", false, timeoutMs, path, method, Map.of()).forRoute(route);
    }
}
