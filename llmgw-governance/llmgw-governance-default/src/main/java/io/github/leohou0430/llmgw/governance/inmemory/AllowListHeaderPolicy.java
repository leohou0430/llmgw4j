package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Config-driven allowlist {@link HeaderPolicy}. Names match
 * case-insensitively; a trailing {@code *} matches any suffix
 * ({@code x-ratelimit-*}). Protected headers are stripped REGARDLESS of the
 * configured lists: the gateway owns Authorization (route credentials, never
 * caller credentials) and transport headers (Content-Type/Accept/Host/
 * Content-Length/hop-by-hop) -- forwarding those would corrupt the proxy
 * contract or leak caller credentials to upstreams.
 *
 * <p>PERF (v13): patterns are compiled ONCE into an exact HashSet + a prefix
 * list, and the deny set is a HashSet -- per-header cost is two hash lookups
 * plus a short prefix scan instead of O(deny-list) string equals. This runs
 * on every request of both data planes.
 */
public class AllowListHeaderPolicy implements HeaderPolicy {

    private static final Set<String> ALWAYS_DENY = Set.of(
            "authorization", "host", "content-length", "content-type", "accept",
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "cookie");

    private static final class AllowPattern {
        final Set<String> exact;
        final List<String> prefixes;

        AllowPattern(List<String> patterns) {
            Set<String> exactSet = new HashSet<>();
            List<String> prefixList = new ArrayList<>();
            for (String pattern : patterns) {
                if (pattern.endsWith("*")) {
                    prefixList.add(pattern.substring(0, pattern.length() - 1));
                } else {
                    exactSet.add(pattern);
                }
            }
            this.exact = Set.copyOf(exactSet);
            this.prefixes = List.copyOf(prefixList);
        }

        boolean matches(String name) {
            if (exact.contains(name)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final AllowPattern upstreamAllow;
    private final AllowPattern clientAllow;

    public AllowListHeaderPolicy(GatewayProperties properties) {
        this.upstreamAllow = new AllowPattern(lower(properties.getHeaders().getUpstreamAllow()));
        this.clientAllow = new AllowPattern(lower(properties.getHeaders().getClientAllow()));
    }

    @Override
    public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> clientHeaders) {
        if (clientHeaders == null || clientHeaders.isEmpty()
                || upstreamAllow.exact.isEmpty() && upstreamAllow.prefixes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = null;
        for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || ALWAYS_DENY.contains(name) || entry.getValue() == null
                    || entry.getValue().isBlank()) {
                continue;
            }
            if (upstreamAllow.matches(name)) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(name, entry.getValue());
            }
        }
        return out == null ? Map.of() : Map.copyOf(out);
    }

    @Override
    public List<String> toClient(Map<String, List<String>> upstreamHeaders) {
        if (upstreamHeaders == null || upstreamHeaders.isEmpty()
                || clientAllow.exact.isEmpty() && clientAllow.prefixes.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : upstreamHeaders.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || ALWAYS_DENY.contains(name)) {
                continue;
            }
            if (clientAllow.matches(name)) {
                // Preserve the upstream's own casing for the copy.
                out.add(entry.getKey());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> lower(List<String> patterns) {
        if (patterns == null) {
            return List.of();
        }
        return patterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
    }
}
