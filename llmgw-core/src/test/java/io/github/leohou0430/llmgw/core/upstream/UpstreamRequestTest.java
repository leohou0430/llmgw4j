package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream request is the security boundary between caller headers and the
 * provider: protected names can never ride through, no matter what a (possibly
 * buggy) HeaderPolicy lets slip.
 */
class UpstreamRequestTest {

    @Test
    void protectedHeadersAreStrippedRegardlessOfPolicy() {
        UpstreamRequest request = new UpstreamRequest("{}", "http://u", "key", "", "m", false, 1000,
                "/chat/completions", UpstreamMethod.POST,
                Map.of("anthropic-version", "2023-06-01",
                        "Authorization", "Bearer caller-secret",
                        "Content-Type", "text/html",
                        "Accept", "text/html",
                        "Host", "evil.example",
                        "Content-Length", "999",
                        "X-Request-Id", "r-1"));
        assertEquals(Map.of("anthropic-version", "2023-06-01", "X-Request-Id", "r-1"),
                request.passHeaders());
        assertFalse(request.passHeaders().containsKey("Authorization"),
                "caller credentials must never reach the upstream");
    }

    @Test
    void nullPassHeadersBecomesEmpty() {
        UpstreamRequest request = new UpstreamRequest("{}", "http://u", "key", "", "m", false, 1000,
                "/chat/completions", UpstreamMethod.POST, null);
        assertTrue(request.passHeaders().isEmpty());
    }

    @Test
    void forRouteAppliesModelRewriteAndKeepsPassHeaders() {
        Route route = new Route("r", "*", "*", "qwen-plus", "http://u", "route-key", "anthropic", 1, List.of());
        UpstreamRequest request = new UpstreamRequest(
                "{\"model\":\"gpt-x\",\"messages\":[]}", "http://u", "key", "", "gpt-x", false, 1000,
                "/chat/completions", UpstreamMethod.POST,
                Map.of("x-request-id", "r-1"));
        UpstreamRequest routed = request.forRoute(route);
        assertTrue(routed.rawJson().contains("qwen-plus"));
        assertEquals("route-key", routed.apiKey(), "route credentials replace caller key");
        assertEquals("anthropic", routed.provider(), "route dialect rides on the request");
        assertEquals("r-1", routed.passHeaders().get("x-request-id"));
    }

    @Test
    void legacyConstructorsDefaultToChatPathAndNoHeaders() {
        UpstreamRequest legacy = new UpstreamRequest("{}", "http://u", "key", "m", true, 5);
        assertEquals("/chat/completions", legacy.path());
        assertTrue(legacy.passHeaders().isEmpty());
    }
}
