package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * @param path        upstream path appended to the route's base URL; each protocol
 *                    has its own suffix, and video polling/content carry the task id
 * @param method      POST for all create-style calls, GET for video poll/content
 * @param passHeaders client headers already filtered by the HeaderPolicy, to be
 *                    attached to the upstream request (v13). Never contains
 *                    Authorization/Content-Type/Accept -- the provider sets
 *                    those itself and defensively strips them here too.
 * @param provider    the route's upstream dialect ("" / "openai" = Bearer,
 *                    "anthropic" = x-api-key); resolved to credential headers
 *                    by UpstreamAuthenticator implementations.
 * @param rawBody     W4.3: binary request body (multipart uploads). When
 *                    non-null it REPLACES rawJson on the wire and the
 *                    target-model rewrite is skipped (bytes pass through
 *                    untouched; rewriting a multipart body is not defined).
 */
public record UpstreamRequest(
        String rawJson,
        String targetUrl,
        String apiKey,
        String provider,
        String model,
        boolean stream,
        int timeoutMs,
        String path,
        UpstreamMethod method,
        Map<String, String> passHeaders,
        byte[] rawBody) {

    public UpstreamRequest(String rawJson, String targetUrl, String apiKey, String provider,
                           String model, boolean stream, int timeoutMs, String path,
                           UpstreamMethod method, Map<String, String> passHeaders) {
        this(rawJson, targetUrl, apiKey, provider, model, stream, timeoutMs, path, method,
                passHeaders, null);
    }

    public UpstreamRequest(String rawJson, String targetUrl, String apiKey, String model,
                           boolean stream, int timeoutMs) {
        this(rawJson, targetUrl, apiKey, "", model, stream, timeoutMs,
                ProtocolKind.CHAT.upstreamPath(), UpstreamMethod.POST, Map.of());
    }

    public UpstreamRequest(String rawJson, String targetUrl, String apiKey, String model,
                           boolean stream, int timeoutMs, String path, UpstreamMethod method) {
        this(rawJson, targetUrl, apiKey, "", model, stream, timeoutMs, path, method, Map.of());
    }

    public UpstreamRequest(String rawJson, String targetUrl, String apiKey, String provider,
                           String model, boolean stream, int timeoutMs, String path,
                           UpstreamMethod method) {
        this(rawJson, targetUrl, apiKey, provider, model, stream, timeoutMs, path, method, Map.of());
    }

    /** Defensive: pass-through headers can never override provider-set headers. */
    public UpstreamRequest {
        if (passHeaders == null) {
            passHeaders = Map.of();
        } else if (!passHeaders.isEmpty()) {
            // W4.3: a BINARY body (multipart upload) NEEDS its original
            // Content-Type — the multipart boundary lives in it. JSON bodies
            // keep the strict strip (the gateway owns that header).
            boolean binaryBody = rawBody != null;
            passHeaders = passHeaders.entrySet().stream()
                    .filter(e -> {
                        String name = e.getKey() == null ? "" : e.getKey().toLowerCase();
                        if (name.equals("authorization") || name.equals("host")
                                || name.equals("content-length")
                                // x-api-key is the anthropic credential header: route-owned.
                                || name.equals("x-api-key")) {
                            return false;
                        }
                        if (!binaryBody && (name.equals("content-type") || name.equals("accept"))) {
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    public UpstreamRequest forRoute(io.github.leohou0430.llmgw.core.routing.Route route) {
        String json = rawJson;
        if (rawBody == null && !route.targetModel().isBlank() && !route.targetModel().equals(model)) {
            // Tree rewrite instead of a regex replace: only the TOP-LEVEL model
            // node changes, and "$"/"\" in the target model are safe. All
            // vendor extension fields survive. Binary bodies skip the rewrite.
            json = ProtocolCodec.withModel(rawJson, route.targetModel());
        }
        return new UpstreamRequest(json, route.targetUrl(), route.apiKey(), route.provider(),
                route.targetModel().isBlank() ? model : route.targetModel(), stream,
                timeoutMs, path, method, passHeaders, rawBody);
    }
}
