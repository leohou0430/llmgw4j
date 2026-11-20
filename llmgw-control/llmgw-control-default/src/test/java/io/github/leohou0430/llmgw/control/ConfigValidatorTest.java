package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.ConfigValidator;
import io.github.leohou0430.llmgw.control.PlatformPolicy;
import io.github.leohou0430.llmgw.control.UpstreamCandidate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.6 seam: config validation against the platform policy — scheme allow-list, private/
 * loopback/metadata address rejection (project cannot relax), timeout/concurrency bounds,
 * and sensitive-header forwarding rejection.
 */
class ConfigValidatorTest {

	private final ConfigValidator validator = new DefaultConfigValidator();

	private static UpstreamCandidate candidate(String scheme, String host, long timeout, int concurrency,
			Set<String> headers) {
		return new UpstreamCandidate("up", scheme, host, timeout, concurrency, headers);
	}

	@Test
	void validCandidatePasses() {
		assertTrue(validator
			.validate(candidate("https", "api.example.com", 30_000L, 100, Set.of()), PlatformPolicy.strict())
			.isEmpty());
	}

	@Test
	void rejectsDisallowedScheme() {
		assertFalse(
				validator.validate(candidate("ftp", "api.example.com", 30_000L, 100, Set.of()), PlatformPolicy.strict())
					.isEmpty());
	}

	@Test
	void rejectsPrivateLoopbackAndMetadataUnlessPlatformAllows() {
		PlatformPolicy strict = PlatformPolicy.strict();
		assertTrue(validator.validate(candidate("https", "10.0.0.5", 30_000L, 100, Set.of()), strict).size() >= 1);
		assertTrue(validator.validate(candidate("https", "127.0.0.1", 30_000L, 100, Set.of()), strict).size() >= 1);
		assertTrue(
				validator.validate(candidate("https", "169.254.169.254", 30_000L, 100, Set.of()), strict).size() >= 1);
		assertTrue(validator.validate(candidate("https", "192.168.1.10", 30_000L, 100, Set.of()), strict).size() >= 1);

		PlatformPolicy relaxed = PlatformPolicy.of(Set.of("https"), true);
		assertTrue(validator.validate(candidate("https", "10.0.0.5", 30_000L, 100, Set.of()), relaxed).isEmpty());
	}

	@Test
	void rejectsOutOfBoundTimeoutAndConcurrency() {
		PlatformPolicy strict = PlatformPolicy.strict();
		assertFalse(validator.validate(candidate("https", "api.example.com", 0L, 100, Set.of()), strict).isEmpty());
		assertFalse(validator.validate(candidate("https", "api.example.com", 10_000_000L, 100, Set.of()), strict)
			.isEmpty());
		assertFalse(validator.validate(candidate("https", "api.example.com", 30_000L, 0, Set.of()), strict).isEmpty());
		assertFalse(validator.validate(candidate("https", "api.example.com", 30_000L, 100_000, Set.of()), strict)
			.isEmpty());
	}

	@Test
	void rejectsSensitiveForwardedHeaders() {
		PlatformPolicy strict = PlatformPolicy.strict();
		assertFalse(
				validator.validate(candidate("https", "api.example.com", 30_000L, 100, Set.of("authorization")), strict)
					.isEmpty());
		assertFalse(validator.validate(candidate("https", "api.example.com", 30_000L, 100, Set.of("Cookie")), strict)
			.isEmpty());
		assertFalse(
				validator.validate(candidate("https", "api.example.com", 30_000L, 100, Set.of("x-admin-token")), strict)
					.isEmpty());
	}

	@Test
	void blankNameIsRejected() {
		assertFalse(validator
			.validate(new UpstreamCandidate(" ", "https", "api.example.com", 30_000L, 100, Set.of()),
					PlatformPolicy.strict())
			.isEmpty());
	}

}