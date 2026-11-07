package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

/**
 * @param path upstream path appended to the route's base URL; each protocol
 *             has its own suffix, and video polling/content carry the task id
 * @param method POST for all create-style calls, GET for video poll/content
 */
public record UpstreamRequest(
        String rawJson,
        String targetUrl,
        String apiKey,
        String model,
        boolean stream,
        int timeoutMs,
        String path,
        UpstreamMethod method) {

    public UpstreamRequest(String rawJson, String targetUrl, String apiKey, String model,
                           boolean stream, int timeoutMs) {
        this(rawJson, targetUrl, apiKey, model, stream, timeoutMs,
                ProtocolKind.CHAT.upstreamPath(), UpstreamMethod.POST);
    }

    public UpstreamRequest forRoute(io.github.leohou0430.llmgw.core.routing.Route route) {
        String json = rawJson;
        if (!route.targetModel().isBlank() && !route.targetModel().equals(model)) {
            // Tree rewrite instead of a regex replace: only the TOP-LEVEL model
            // node changes, and "$"/"\" in the target model are safe. All
            // vendor extension fields survive.
            json = ProtocolCodec.withModel(rawJson, route.targetModel());
        }
        return new UpstreamRequest(json, route.targetUrl(), route.apiKey(),
                route.targetModel().isBlank() ? model : route.targetModel(), stream,
                timeoutMs, path, method);
    }
}
