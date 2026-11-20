package io.github.leohou0430.llmgw.control;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.leohou0430.llmgw.control.local.DefaultConfigValidator;
import io.github.leohou0430.llmgw.control.local.RouteCandidateValidator;

class RouteCandidateValidatorTest {

	private final RouteCandidateValidator validator = new RouteCandidateValidator(new DefaultConfigValidator());

	private static Map<String, Object> spec(String scheme, String host) {
		return Map.of("name", "deepseek-chat", "scheme", scheme, "host", host, "timeoutMillis", 30_000,
				"maxConcurrency", 100);
	}

	@Test
	void acceptsAWellFormedHttpsCandidate() {
		assertTrue(validator.check(spec("https", "api.deepseek.com"), PlatformPolicy.strict()).isEmpty());
	}

	@Test
	void rejectsDisallowedSchemeAndPrivateHost() {
		List<String> plain = validator.check(spec("http", "api.deepseek.com"), PlatformPolicy.strict());
		assertTrue(plain.stream().anyMatch(v -> v.contains("scheme not allowed")), plain.toString());

		List<String> internal = validator.check(spec("https", "10.0.0.5"), PlatformPolicy.strict());
		assertTrue(internal.stream().anyMatch(v -> v.contains("private/loopback/metadata")), internal.toString());
	}

	@Test
	void rejectsOutOfBoundsTimeoutConcurrencyAndSensitiveHeaders() {
		List<String> violations = validator.check(
				Map.of("name", "x", "scheme", "https", "host", "api.deepseek.com", "timeoutMillis", 10,
						"maxConcurrency", 0, "forwardedHeaders", List.of("Authorization", "X-Trace")),
				PlatformPolicy.strict());
		assertTrue(violations.stream().anyMatch(v -> v.contains("timeout out of bounds")), violations.toString());
		assertTrue(violations.stream().anyMatch(v -> v.contains("maxConcurrency out of bounds")),
				violations.toString());
		assertTrue(violations.stream().anyMatch(v -> v.contains("sensitive header")), violations.toString());
	}

	@Test
	void missingSpecIsRejectedRatherThanTreatedAsValid() {
		assertTrue(!validator.check(null, PlatformPolicy.strict()).isEmpty());
	}

}
