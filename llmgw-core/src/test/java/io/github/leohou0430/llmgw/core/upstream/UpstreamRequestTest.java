package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream request is the security boundary between caller headers and the provider:
 * protected names can never ride through, no matter what a (possibly buggy) HeaderPolicy
 * lets slip.
 */
class UpstreamRequestTest {

	@Test
	void batchCreationMustNotAcquireATopLevelModelFromTheRoute() {
		var route = new Route("r", "*", "*", "upstream-model", "http://u", "route-key", "openai", 1, List.of());
		String body = "{\"input_file_id\":\"file_1\",\"endpoint\":\"/v1/chat/completions\",\"completion_window\":\"24h\"}";
		var request = new UpstreamRequest(body, "http://u", "key", "", "batch", false, 1000, "/batches",
				UpstreamMethod.POST, Map.of())
			.forRoute(route);
		assertEquals(body, request.rawJson());
		assertEquals("route-key", request.apiKey());
	}

	@Test
	void protectedHeadersAreStrippedRegardlessOfPolicy() {
		UpstreamRequest request = new UpstreamRequest("{}", "http://u", "key", "", "m", false, 1000,
				"/chat/completions", UpstreamMethod.POST,
				Map.of("anthropic-version", "2023-06-01", "Authorization", "Bearer caller-secret", "Content-Type",
						"text/html", "Accept", "text/html", "Host", "evil.example", "Content-Length", "999",
						"X-Request-Id", "r-1"));
		assertEquals(Map.of("anthropic-version", "2023-06-01", "X-Request-Id", "r-1"), request.passHeaders());
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
		UpstreamRequest request = new UpstreamRequest("{\"model\":\"gpt-x\",\"messages\":[]}", "http://u", "key", "",
				"gpt-x", false, 1000, "/chat/completions", UpstreamMethod.POST, Map.of("x-request-id", "r-1"));
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

	@Test
	void streamingBinaryBodyKeepsContentTypeAndSkipsModelRewrite() {
		Route route = new Route("r", "*", "*", "upstream-model", "http://u", "route-key", "openai", 1, List.of());
		ByteStream body = ByteStream.ofByteArray(new byte[] { 1, 2, 3 });
		UpstreamRequest request = new UpstreamRequest("", "http://u", "key", "", "gpt-x", false, 1000, "/files",
				UpstreamMethod.POST, Map.of("Content-Type", "multipart/form-data; boundary=abc", "Authorization",
						"Bearer caller-secret", "Content-Length", "999", "X-Api-Key", "caller-key"),
				body, 3);
		// A binary body keeps its multipart Content-Type (the boundary lives there)...
		assertEquals("multipart/form-data; boundary=abc", request.passHeaders().get("Content-Type"));
		// ...but protected names still never ride through.
		assertFalse(request.passHeaders().containsKey("Authorization"));
		assertFalse(request.passHeaders().containsKey("Content-Length"));
		assertFalse(request.passHeaders().containsKey("X-Api-Key"));

		UpstreamRequest routed = request.forRoute(route);
		assertSame(body, routed.body(), "the same cold stream rides through routing");
		assertEquals(3, routed.bodyLength());
		assertEquals("", routed.rawJson(), "a binary body has no rewritable top-level model");
		assertEquals("route-key", routed.apiKey());
	}

}
