package io.github.leohou0430.llmgw.core.routing;

import java.util.List;

/**
 * One upstream route. {@code protocol} pins the route to a single endpoint
 * protocol ("chat"/"responses"/"messages"/"images"/"videos"); "" or "*" match
 * every protocol. Pass-through parity means protocol is part of route identity:
 * the same model name under two protocols can target two different upstreams.
 */
public record Route(
        String id,
        String protocol,
        String model,
        String targetModel,
        String targetUrl,
        String apiKey,
        int weight,
        List<String> fallbackIds) {

    public Route(String id, String model, String targetModel, String targetUrl,
                 String apiKey, int weight, List<String> fallbackIds) {
        this(id, "*", model, targetModel, targetUrl, apiKey, weight, fallbackIds);
    }

    /** "" normalizes to "*"; unknown values are rejected by RouteTable.of. */
    public boolean matches(String protocolWireName) {
        return protocol == null || protocol.isBlank() || "*".equals(protocol)
                || protocol.equalsIgnoreCase(protocolWireName);
    }
}
