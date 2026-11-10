package io.github.leohou0430.llmgw.provider.anthropic;

import io.github.leohou0430.llmgw.provider.UpstreamAuthenticator;

import java.util.Map;

/**
 * Anthropic-native credential dialect: {@code x-api-key: <key>} plus the
 * required {@code anthropic-version} header -- NOT {@code Authorization:
 * Bearer}. Selected by routes declaring {@code provider: anthropic}.
 *
 * <p>This is the first real UpstreamAuthenticator adapter, which turns the
 * provider seam from hypothetical to real. Error semantics need no adapter:
 * Anthropic's 429/529(overloaded)/5xx already map onto the gateway's retryable
 * classification, and 4xx (400 invalid_request / 401 authentication /
 * 403 permission / 404 not_found) correctly do NOT retry.
 */
public class AnthropicAuthenticator implements UpstreamAuthenticator {

    public static final String PROVIDER = "anthropic";
    /** The oldest version the API still accepts; explicit beats omitted. */
    public static final String API_VERSION = "2023-06-01";

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    @Override
    public Map<String, String> authHeaders(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            return Map.of();
        }
        return Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
    }
}
