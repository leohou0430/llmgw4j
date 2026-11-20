package io.github.leohou0430.llmgw.provider.gemini;

import io.github.leohou0430.llmgw.provider.UpstreamAuthenticator;

import java.util.Map;

/**
 * Task-7 Gemini upstream credential policy: the ROUTE's API key rides the
 * {@code x-goog-api-key} header; the CALLER's key is never forwarded upstream (the data
 * planes strip caller authorization headers before building the upstream request). Blank
 * route key ⇒ no credential header.
 */
public final class GeminiAuthenticator implements UpstreamAuthenticator {

	@Override
	public boolean supports(String provider) {
		return "gemini".equals(provider);
	}

	@Override
	public Map<String, String> authHeaders(String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			return Map.of();
		}
		return Map.of("x-goog-api-key", apiKey);
	}

}
