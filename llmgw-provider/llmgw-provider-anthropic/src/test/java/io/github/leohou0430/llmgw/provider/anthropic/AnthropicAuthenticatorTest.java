package io.github.leohou0430.llmgw.provider.anthropic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The anthropic credential dialect: x-api-key + anthropic-version, no Bearer. */
class AnthropicAuthenticatorTest {

    private final AnthropicAuthenticator authenticator = new AnthropicAuthenticator();

    @Test
    void supportsOnlyTheAnthropicDialect() {
        assertTrue(authenticator.supports("anthropic"));
        assertTrue(authenticator.supports("Anthropic"));
        assertFalse(authenticator.supports(""));
        assertFalse(authenticator.supports("openai"));
        assertFalse(authenticator.supports(null));
    }

    @Test
    void credentialHeadersUseXApiKeyAndVersion() {
        Map<String, String> headers = authenticator.authHeaders("sk-ant-xyz");
        assertEquals("sk-ant-xyz", headers.get("x-api-key"));
        assertEquals("2023-06-01", headers.get("anthropic-version"));
        assertFalse(headers.containsKey("Authorization"),
                "the anthropic dialect never sends Bearer");
    }

    @Test
    void blankOrPlaceholderKeysYieldNoCredentialHeaders() {
        assertTrue(authenticator.authHeaders("").isEmpty());
        assertTrue(authenticator.authHeaders(null).isEmpty());
        assertTrue(authenticator.authHeaders("${DASHSCOPE_API_KEY:}").isEmpty(),
                "unresolved env placeholders count as unset");
    }
}
