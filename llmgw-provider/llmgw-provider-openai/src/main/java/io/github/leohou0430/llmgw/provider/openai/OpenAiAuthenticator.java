package io.github.leohou0430.llmgw.provider.openai;

import io.github.leohou0430.llmgw.provider.UpstreamAuthenticator;
import java.util.Map;

/** Stateless OpenAI-compatible credential dialect, loaded by project-owned named extensions. */
public final class OpenAiAuthenticator implements UpstreamAuthenticator {
    @Override public boolean supports(String provider) {
        return provider == null || provider.isBlank() || switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "openai", "openai-compatible", "default" -> true;
            default -> false;
        };
    }
    @Override public Map<String, String> authHeaders(String apiKey) {
        return apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")
                ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
    }
}
