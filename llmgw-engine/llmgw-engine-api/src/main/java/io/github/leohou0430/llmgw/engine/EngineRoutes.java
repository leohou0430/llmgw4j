package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;

import java.util.ArrayList;
import java.util.List;

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
     * fields survive byte-for-byte.
     */
    public static UpstreamRequest request(String rawJson, ParsedRequest parsed,
                                          Route route, int timeoutMs) {
        return new UpstreamRequest(rawJson, route.targetUrl(), route.apiKey(),
                parsed.model(), parsed.stream(), timeoutMs).forRoute(route);
    }
}
