package io.github.leohou0430.llmgw.core.routing;

import java.util.List;

/**
 * One upstream route. {@code protocol} pins the route to a single endpoint
 * protocol ("chat"/"responses"/"messages"/"images"/"videos"); "" or "*" match
 * every protocol. Pass-through parity means protocol is part of route identity:
 * the same model name under two protocols can target two different upstreams.
 *
 * <p>{@code provider} (v13) names the upstream dialect for AUTH purposes:
 * "" / "openai" use Bearer; "anthropic" uses x-api-key + anthropic-version
 * (see {@code UpstreamAuthenticator} implementations). It is a routing
 * dimension, NOT a protocol translation: the request bytes still pass through
 * untouched, and the route's protocol must match the client's protocol.
 */
public record Route(
        String id,
        String protocol,
        String model,
        String targetModel,
        String targetUrl,
        String apiKey,
        String provider,
        int weight,
        List<String> fallbackIds) {

    public Route(String id, String model, String targetModel, String targetUrl,
                 String apiKey, int weight, List<String> fallbackIds) {
        this(id, "*", model, targetModel, targetUrl, apiKey, "", weight, fallbackIds);
    }

    public Route(String id, String protocol, String model, String targetModel,
                 String targetUrl, String apiKey, int weight, List<String> fallbackIds) {
        this(id, protocol, model, targetModel, targetUrl, apiKey, "", weight, fallbackIds);
    }

    /** "" normalizes to "*"; unknown values are rejected by RouteTable.of. */
    public boolean matches(String protocolWireName) {
        return protocol == null || protocol.isBlank() || "*".equals(protocol)
                || protocol.equalsIgnoreCase(protocolWireName);
    }

    /**
     * Model match with three specificity classes (v13): exact, prefix
     * ({@code claude-*}), wildcard ({@code *}). Prefix routing is what makes
     * provider/model-family lanes (and canary splits within a family) expressible
     * without exact-name enumeration.
     */
    public boolean matchesModel(String model) {
        String pattern = this.model;
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return model != null && !model.isBlank() && model.startsWith(prefix);
        }
        return pattern.equals(model);
    }

    /** 0 = exact, 1 = prefix, 2 = wildcard. Lower binds tighter. */
    public int modelSpecificity() {
        String pattern = this.model;
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return 2;
        }
        return pattern.endsWith("*") ? 1 : 0;
    }
}
