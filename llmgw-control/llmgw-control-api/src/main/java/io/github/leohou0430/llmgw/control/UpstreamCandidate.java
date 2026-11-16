package io.github.leohou0430.llmgw.control;

import java.util.Set;

/**
 * A proposed upstream configuration to validate. Fields are structural (scheme/host kept
 * separate so no URL user-info probing is possible); {@code forwardedHeaders} lists header
 * names copied from the client to the upstream.
 */
public record UpstreamCandidate(
        String name,
        String scheme,
        String host,
        long timeoutMillis,
        int maxConcurrency,
        Set<String> forwardedHeaders) {

    public UpstreamCandidate {
        forwardedHeaders = forwardedHeaders == null ? Set.of() : Set.copyOf(forwardedHeaders);
    }

    public static UpstreamCandidate of(String name, String scheme, String host, long timeoutMillis, int maxConcurrency) {
        return new UpstreamCandidate(name, scheme, host, timeoutMillis, maxConcurrency, Set.of());
    }
}