package io.github.leohou0430.llmgw.provider.gemini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务7认证合同：路由密钥走 x-goog-api-key；调用方密钥永不入上游头。 */
class GeminiAuthenticatorTest {

	private final GeminiAuthenticator auth = new GeminiAuthenticator();

	@Test
	void ownsOnlyTheGeminiDialect() {
		assertTrue(auth.supports("gemini"));
		assertFalse(auth.supports("openai"));
		assertFalse(auth.supports("anthropic"));
	}

	@Test
	void routeKeyRidesGoogApiKeyHeader() {
		var headers = auth.authHeaders("route-secret-123");
		assertEquals("route-secret-123", headers.get("x-goog-api-key"));
		assertEquals(1, headers.size(), "不带 Authorization/Bearer");
		assertFalse(headers.containsKey("Authorization"));
		assertFalse(headers.containsKey("x-api-key"));
	}

	@Test
	void blankRouteKeySendsNoCredentialHeader() {
		assertTrue(auth.authHeaders("").isEmpty());
		assertTrue(auth.authHeaders(null).isEmpty());
	}

}
