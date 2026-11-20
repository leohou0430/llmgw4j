package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.12: the conversion seam builds its upstream request from the ALREADY converted
 * payload. The upstream path/body come from the prepared conversion, the model stays the
 * CALLER model (the upstream rewrite happened inside the codec), and no second
 * target-model rewrite runs.
 */
class EngineRoutesConversionTest {

	private static final String CALLER_MODEL = "client-model";

	private static final String UPSTREAM_MODEL = "deepseek-chat";

	/** Prepared upstream payload: the codec already rewrote the model. */
	private static final String PREPARED_JSON = "{\"model\":\"" + UPSTREAM_MODEL
			+ "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

	private static Route route() {
		return new Route("r", "*", CALLER_MODEL, UPSTREAM_MODEL, "https://api.deepseek.com", "sk-test", "", 1,
				List.of(), "chat");
	}

	private static ParsedRequest parsed(ProtocolKind kind, boolean stream) {
		return new ParsedRequest(PREPARED_JSON, kind, CALLER_MODEL, stream, "hi", 1, 1, null);
	}

	@Test
	void convertedRequestSendsThePreparedPayloadVerbatimWithoutATargetModelRewrite() {
		UpstreamRequest request = EngineRoutes.convertedRequest(PREPARED_JSON, "/chat/completions",
				parsed(ProtocolKind.CHAT, false), route(), 1500, Map.of("x-request-id", "abc"));

		assertEquals(PREPARED_JSON, request.rawJson(), "the prepared payload must reach the wire byte-for-byte");
		assertEquals("/chat/completions", request.path());
		assertEquals(CALLER_MODEL, request.model(), "the caller model, never the rewritten upstream model");
		assertEquals("https://api.deepseek.com", request.targetUrl());
		assertEquals("sk-test", request.apiKey());
		assertEquals(1500, request.timeoutMs());
		assertEquals(UpstreamMethod.POST, request.method());
		assertFalse(request.stream());
		assertEquals("abc", request.passHeaders().get("x-request-id"));
	}

	@Test
	void convertedRequestUsesThePreparedPathNotTheEntryProtocolPathAndKeepsTheStreamFlag() {
		// A Responses entry converted to DeepSeek Chat: the upstream path is the CHAT
		// path even
		// though the caller protocol is responses, and stream=true must survive.
		UpstreamRequest request = EngineRoutes.convertedRequest(PREPARED_JSON, "/chat/completions",
				parsed(ProtocolKind.RESPONSES, true), route(), 900, Map.of());

		assertEquals("/chat/completions", request.path());
		assertEquals(ProtocolKind.RESPONSES.upstreamPath(), "/responses");
		assertTrue(request.stream());
		assertEquals(CALLER_MODEL, request.model());
	}

	@Test
	void passthroughRequestStillRewritesTheTargetModelAndKeepsTheEntryPath() {
		UpstreamRequest request = EngineRoutes.request(PREPARED_JSON, parsed(ProtocolKind.CHAT, false), route(), 1500,
				Map.of());

		assertEquals(UPSTREAM_MODEL, request.model());
		assertTrue(request.rawJson().contains(UPSTREAM_MODEL), "passthrough keeps the tree rewrite");
		assertEquals(ProtocolKind.CHAT.upstreamPath(), request.path());
	}

}