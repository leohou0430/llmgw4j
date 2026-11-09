package io.github.leohou0430.llmgw.core.upstream;

import java.util.Map;

/**
 * Per-provider upstream credential policy (v13). A route's {@code provider}
 * names the upstream dialect; the matching authenticator decides which
 * credential headers carry the route's API key. This is the SEAM that lets a
 * first real provider adapter (llmgw-provider-anthropic: {@code x-api-key} +
 * {@code anthropic-version}) exist without duplicating the HTTP machinery --
 * protocol remains a ROUTING dimension and the bytes still pass through.
 *
 * <p>Implementations must be cheap and stateless; called per request at
 * request-build time. When no authenticator supports a provider, the gateway
 * falls back to the OpenAI-compatible {@code Authorization: Bearer} default.
 */
public interface UpstreamAuthenticator {

    /** true when this authenticator owns the named provider dialect. */
    boolean supports(String provider);

    /**
     * Credential headers for one upstream call. {@code apiKey} is the ROUTE's
     * key (never the caller's); it may be blank, in which case the
     * implementation should return an empty map (no credential header).
     */
    Map<String, String> authHeaders(String apiKey);
}
