package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.12 seam contract: a pre-header conversion failure must be rendered in the CALLER's
 * entry error shape (matrix §10.2) with the exception's own HTTP status, without the
 * engine re-scanning codecs. The orchestration owns the discovered entry codecs, so it
 * renders the error.
 */
class ConversionErrorShapeTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static ConversionService service(ExtensionContext context) {
		context.seal();
		return context.get(ConversionService.class);
	}

	private static ExtensionContext context() {
		var context = new ExtensionContext();
		context.external(GatewayProperties.class, new GatewayProperties());
		context.loadModule("conversion");
		return context;
	}

	private static CapabilityException unknownField() {
		return new CapabilityException(CapabilityException.UNKNOWN_FIELD, 400, "messages[0].temperature",
				"上游无 temperature，不可表达");
	}

	@Test
	void chatEntryErrorJsonUsesTheChatErrorShape() {
		try (var context = context()) {
			JsonNode root = MAPPER.readTree(service(context).errorJson(ProtocolKind.CHAT, unknownField()));
			JsonNode error = root.path("error");
			assertEquals("capability_unknown_field", error.path("code").asString());
			assertEquals("invalid_request_error", error.path("type").asString());
			assertEquals("messages[0].temperature", error.path("param").asString());
			assertTrue(error.path("message").asString().contains("temperature"));
		}
	}

	@Test
	void responsesEntryErrorJsonUsesTheResponsesErrorShape() {
		try (var context = context()) {
			JsonNode error = MAPPER.readTree(service(context).errorJson(ProtocolKind.RESPONSES, unknownField()))
				.path("error");
			assertEquals("capability_unknown_field", error.path("code").asString());
			assertEquals("messages[0].temperature", error.path("param").asString());
			assertTrue(error.has("type"), "responses error carries a type");
			assertTrue(error.path("message").asString().contains("temperature"));
		}
	}

	@Test
	void messagesEntryErrorJsonUsesTheMessagesErrorShape() {
		try (var context = context()) {
			JsonNode root = MAPPER.readTree(service(context).errorJson(ProtocolKind.MESSAGES, unknownField()));
			assertEquals("error", root.path("type").asString());
			assertEquals("capability_unknown_field", root.path("error").path("type").asString());
			assertTrue(root.path("error").path("message").asString().contains("temperature"));
		}
	}

	@Test
	void anUpstreamFailureRendersAsUpstreamErrorWithStatus502InEveryEntryShape() {
		try (var context = context()) {
			ConversionService service = service(context);
			CapabilityException upstream = new CapabilityException(UpstreamException.UPSTREAM_ERROR,
					new UpstreamException("upstream aborted").status(), "choices[0]", "upstream aborted");
			assertEquals(502, upstream.status());
			assertEquals("server_error",
					MAPPER.readTree(service.errorJson(ProtocolKind.CHAT, upstream))
						.path("error")
						.path("type")
						.asString());
			assertEquals("upstream_error",
					MAPPER.readTree(service.errorJson(ProtocolKind.CHAT, upstream))
						.path("error")
						.path("code")
						.asString());
			assertEquals("error",
					MAPPER.readTree(service.errorJson(ProtocolKind.MESSAGES, upstream)).path("type").asString());
			assertEquals("upstream_error",
					MAPPER.readTree(service.errorJson(ProtocolKind.RESPONSES, upstream))
						.path("error")
						.path("code")
						.asString());
		}
	}

	@Test
	void anEntryProtocolWithoutACodecFallsBackToTheGatewayErrorShape() {
		try (var context = context()) {
			// No entry codec serves IMAGES: the seam still answers with a stable error
			// body.
			String json = service(context).errorJson(ProtocolKind.IMAGES, unknownField());
			assertTrue(MAPPER.readTree(json).path("error").path("message").asString().contains("temperature"), json);
		}
	}

	@Test
	void aCodecLessServiceKeepsTheGatewayErrorShapeByDefault() {
		// The default rendering must not require codec discovery: a replacement service
		// (the
		// native replace path) still answers with the gateway error shape.
		ConversionService codecLess = new ConversionService() {
			@Override
			public boolean applies(Route route) {
				return false;
			}

			@Override
			public PreparedConversion prepare(ParsedRequest parsed, Route route, String tenantId, String projectId,
					DegradationGrant grant) {
				throw new UnsupportedOperationException();
			}
		};
		String json = codecLess.errorJson(ProtocolKind.CHAT, unknownField());
		assertTrue(MAPPER.readTree(json).path("error").path("message").asString().contains("temperature"), json);
	}

}