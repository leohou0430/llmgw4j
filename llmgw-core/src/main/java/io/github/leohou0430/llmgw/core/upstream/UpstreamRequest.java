package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;

public record UpstreamRequest(
        String rawJson,
        String targetUrl,
        String apiKey,
        String model,
        boolean stream,
        int timeoutMs) {

    public UpstreamRequest forRoute(io.github.leohou0430.llmgw.core.routing.Route route) {
        String json = rawJson;
        if (!route.targetModel().isBlank() && !route.targetModel().equals(model)) {
            // Tree rewrite instead of a regex replace: only the TOP-LEVEL model
            // node changes, and "$"/"\" in the target model are safe. All
            // vendor extension fields survive.
            json = ProtocolCodec.withModel(rawJson, route.targetModel());
        }
        return new UpstreamRequest(json, route.targetUrl(), route.apiKey(),
                route.targetModel().isBlank() ? model : route.targetModel(), stream, timeoutMs);
    }
}
