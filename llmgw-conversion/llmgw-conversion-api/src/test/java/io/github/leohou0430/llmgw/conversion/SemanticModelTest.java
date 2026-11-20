package io.github.leohou0430.llmgw.conversion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticModelTest {

	private static SemanticRequest request(List<SemanticMessage> messages, List<ToolSpec> tools, List<String> stop) {
		return new SemanticRequest("deepseek-flash", messages, tools, "auto", Boolean.TRUE, 1024, true, "high", stop,
				0.7, 0.95, "json_object", "user-1", Boolean.FALSE);
	}

	private static SemanticRequest withIncludeUsage(Boolean includeUsage) {
		return new SemanticRequest("deepseek-flash",
				List.of(new SemanticMessage(Role.USER, List.of(new TextPart("hi")))), null, null, null, null, true,
				null, null, null, null, null, null, includeUsage);
	}

	@Test
	void includeUsagePreferenceIsCarriedLosslesslyAndNullMeansUnspecified() {
		assertEquals(Boolean.FALSE, withIncludeUsage(Boolean.FALSE).includeUsage());
		assertEquals(Boolean.TRUE, withIncludeUsage(Boolean.TRUE).includeUsage());
		assertNull(withIncludeUsage(null).includeUsage(), "null＝调用方未指定，不得默认成 true/false");
		assertNotEquals(withIncludeUsage(Boolean.FALSE), withIncludeUsage(Boolean.TRUE));
		assertNotEquals(withIncludeUsage(Boolean.FALSE), withIncludeUsage(null));
		assertEquals(withIncludeUsage(Boolean.TRUE), withIncludeUsage(Boolean.TRUE));
		// 新增分量不得改动既有分量的位置与取值。
		SemanticRequest request = withIncludeUsage(Boolean.TRUE);
		assertEquals("deepseek-flash", request.model());
		assertTrue(request.stream());
		assertNull(request.userId());
		assertEquals(List.of(), request.tools());
	}

	@Test
	void multiTurnMixedContentPreservesOrder() {
		List<ContentPart> userParts = List.of(new TextPart("look at this"),
				new ImagePart("image/png", "BASE64DATA", null, "high"));
		List<ContentPart> assistantParts = List.of(new ThinkingPart("let me reason", "llmgw1:envelope"),
				new TextPart("I will call a tool"), new ToolCallPart("call_1", "get_weather", "{\"city\":\"SF\"}"));
		List<SemanticMessage> messages = List.of(new SemanticMessage(Role.SYSTEM, List.of(new TextPart("be helpful"))),
				new SemanticMessage(Role.USER, userParts), new SemanticMessage(Role.ASSISTANT, assistantParts),
				new SemanticMessage(Role.TOOL, List.of(new ToolResultPart("call_1", "sunny", false))));
		SemanticRequest request = new SemanticRequest("deepseek-flash", messages,
				List.of(new ToolSpec("get_weather", "weather", "{}")), "tool:get_weather", Boolean.TRUE, 1024, true,
				"high", List.of("STOP"), 0.7, 0.95, "json_object", "user-1", Boolean.TRUE);

		assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL),
				request.messages().stream().map(SemanticMessage::role).toList());
		assertEquals("be helpful", ((TextPart) request.messages().get(0).parts().get(0)).text());

		List<ContentPart> gotUser = request.messages().get(1).parts();
		assertEquals(2, gotUser.size());
		assertEquals("look at this", ((TextPart) gotUser.get(0)).text());
		ImagePart image = (ImagePart) gotUser.get(1);
		assertEquals("image/png", image.mediaType());
		assertEquals("BASE64DATA", image.base64Data());
		assertEquals("high", image.detail());
		assertNull(image.fileRef());

		List<ContentPart> gotAssistant = request.messages().get(2).parts();
		assertEquals(3, gotAssistant.size());
		assertEquals("let me reason", ((ThinkingPart) gotAssistant.get(0)).reasoning());
		assertEquals("llmgw1:envelope", ((ThinkingPart) gotAssistant.get(0)).continuationToken());
		assertEquals("I will call a tool", ((TextPart) gotAssistant.get(1)).text());
		ToolCallPart call = (ToolCallPart) gotAssistant.get(2);
		assertEquals("call_1", call.id());
		assertEquals("get_weather", call.name());
		assertEquals("{\"city\":\"SF\"}", call.argumentsJson());

		ToolResultPart result = (ToolResultPart) request.messages().get(3).parts().get(0);
		assertEquals("call_1", result.toolCallId());
		assertEquals("sunny", result.content());
		assertFalse(result.isError());

		assertEquals("deepseek-flash", request.model());
		assertEquals(List.of(new ToolSpec("get_weather", "weather", "{}")), request.tools());
		assertEquals("tool:get_weather", request.toolChoice());
		assertEquals(Boolean.TRUE, request.thinkingEnabled());
		assertEquals(1024, request.maxOutputTokens().intValue());
		assertTrue(request.stream());
		assertEquals("high", request.thinkingEffort());
		assertEquals(List.of("STOP"), request.stop());
		assertEquals(0.7, request.temperature().doubleValue());
		assertEquals(0.95, request.topP().doubleValue());
		assertEquals("json_object", request.responseFormat());
		assertEquals("user-1", request.userId());
		assertEquals(Boolean.TRUE, request.includeUsage());
	}

	@Test
	void imagePartCarriesDetailAndRequiresARepresentation() {
		ImagePart inline = new ImagePart("image/jpeg", "AAAA", null, "low");
		assertEquals("image/jpeg", inline.mediaType());
		assertEquals("AAAA", inline.base64Data());
		assertEquals("low", inline.detail());

		ImagePart referenced = new ImagePart(null, null, "file-123", "auto");
		assertEquals("file-123", referenced.fileRef());
		assertEquals("auto", referenced.detail());
		assertNull(referenced.mediaType());
		assertNull(referenced.base64Data());

		assertNull(new ImagePart("image/png", "AAAA", null, null).detail());
		assertEquals("original", new ImagePart("image/png", "AAAA", null, "original").detail());
		// detail 取值由编解码层校验，语义层原样承载（含上游将来新增取值）。
		assertEquals("xhigh", new ImagePart("image/png", "AAAA", null, "xhigh").detail());

		IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
				() -> new ImagePart(null, null, null, "high"));
		assertTrue(empty.getMessage().contains("mediaType"), empty.getMessage());
		assertTrue(empty.getMessage().contains("fileRef"), empty.getMessage());
		assertThrows(IllegalArgumentException.class, () -> new ImagePart("image/png", null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new ImagePart(null, "AAAA", null, null));
	}

	@Test
	void usageUnknownIsNullNeverZero() {
		Usage unknown = new Usage(null, null, null, null);
		assertNull(unknown.inputTokens());
		assertNull(unknown.outputTokens());
		assertNull(unknown.reasoningTokens());
		assertNull(unknown.cachedInputTokens());

		Usage partial = new Usage(10L, null, null, null);
		assertEquals(10L, partial.inputTokens().longValue());
		assertNull(partial.outputTokens());
		assertNull(partial.reasoningTokens());
		assertNull(partial.cachedInputTokens());

		Usage cached = new Usage(100L, 20L, 5L, 80L);
		assertEquals(100L, cached.inputTokens().longValue());
		assertEquals(20L, cached.outputTokens().longValue());
		assertEquals(5L, cached.reasoningTokens().longValue());
		assertEquals(80L, cached.cachedInputTokens().longValue());

		Usage zero = new Usage(0L, 0L, 0L, 0L);
		assertEquals(0L, zero.inputTokens().longValue());
		assertEquals(0L, zero.cachedInputTokens().longValue());
		assertNotEquals(unknown, zero);
		assertNotEquals(partial, new Usage(10L, null, null, 0L));
		assertEquals(cached, new UsageEvent(cached).usage());
	}

	@Test
	void terminalEventKeepsUpstreamStopReasonWithoutDefaulting() {
		TerminalEvent unknown = new TerminalEvent(StopReason.UNKNOWN);
		assertEquals(StopReason.UNKNOWN, unknown.stop());
		assertNotEquals(unknown, new TerminalEvent(StopReason.COMPLETED));
		assertEquals(StopReason.LENGTH, new TerminalEvent(StopReason.LENGTH).stop());
		assertEquals(StopReason.CONTENT_FILTER, new TerminalEvent(StopReason.CONTENT_FILTER).stop());
		assertEquals(StopReason.TOOL_CALL, new TerminalEvent(StopReason.TOOL_CALL).stop());
	}

	@Test
	void thinkingPartKeepsReasoningAndOptionalContinuationToken() {
		ThinkingPart nativeThinking = new ThinkingPart("reasoning text", null);
		assertEquals("reasoning text", nativeThinking.reasoning());
		assertNull(nativeThinking.continuationToken());
		assertEquals("llmgw1:abc", new ThinkingPart("r", "llmgw1:abc").continuationToken());
		assertEquals("", new ThinkingPart("", null).reasoning());
	}

	@Test
	void requiredFieldsRejectNullNamingTheField() {
		assertEquals("text", assertThrows(NullPointerException.class, () -> new TextPart(null)).getMessage());
		assertEquals("reasoning",
				assertThrows(NullPointerException.class, () -> new ThinkingPart(null, null)).getMessage());
		assertEquals("id",
				assertThrows(NullPointerException.class, () -> new ToolCallPart(null, "n", "{}")).getMessage());
		assertEquals("name",
				assertThrows(NullPointerException.class, () -> new ToolCallPart("id", null, "{}")).getMessage());
		assertEquals("toolCallId",
				assertThrows(NullPointerException.class, () -> new ToolResultPart(null, "c", false)).getMessage());
		assertEquals("name",
				assertThrows(NullPointerException.class, () -> new ToolSpec(null, "d", "{}")).getMessage());
		assertEquals("role",
				assertThrows(NullPointerException.class, () -> new SemanticMessage(null, List.of(new TextPart("t"))))
					.getMessage());
		assertEquals("stop", assertThrows(NullPointerException.class, () -> new TerminalEvent(null)).getMessage());
		assertEquals("usage", assertThrows(NullPointerException.class, () -> new UsageEvent(null)).getMessage());
		assertEquals("text", assertThrows(NullPointerException.class, () -> new TextDelta(null)).getMessage());
		assertEquals("reasoning", assertThrows(NullPointerException.class, () -> new ThinkingDelta(null)).getMessage());
	}

	@Test
	void toolCallDeltaRejectsNegativeIndex() {
		IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
				() -> new ToolCallDelta(-1, "call_1", "get_weather", null));
		assertTrue(negative.getMessage().contains("index"), negative.getMessage());
		assertEquals(0, new ToolCallDelta(0, null, null, null).index());
		assertEquals(7, new ToolCallDelta(7, null, null, "{}").index());
	}

	@Test
	void deltaPayloadsAllowEmptyString() {
		assertEquals("", new TextDelta("").text());
		assertEquals("", new ThinkingDelta("").reasoning());
		assertEquals("", new TextPart("").text());
	}

	@Test
	void requiredCollectionsRejectNullNamingTheField() {
		assertEquals("messages",
				assertThrows(NullPointerException.class, () -> request(null, null, null)).getMessage());
		assertEquals("parts",
				assertThrows(NullPointerException.class, () -> new SemanticMessage(Role.USER, null)).getMessage());
		assertEquals("messages", assertThrows(NullPointerException.class,
				() -> new SemanticResponse("id", null, StopReason.COMPLETED, null))
			.getMessage());
	}

	@Test
	void optionalCollectionsNormalizeNullToEmpty() {
		List<SemanticMessage> messages = List.of(new SemanticMessage(Role.USER, List.of(new TextPart("hi"))));
		SemanticRequest request = request(messages, null, null);
		assertEquals(List.of(), request.tools());
		assertEquals(List.of(), request.stop());
		assertEquals(request(messages, List.of(), List.of()), request);
	}

	@Test
	void collectionsAreDefensivelyCopiedAndUnmodifiable() {
		List<ContentPart> parts = new ArrayList<>();
		parts.add(new TextPart("original"));
		SemanticMessage message = new SemanticMessage(Role.USER, parts);
		parts.add(new TextPart("mutated"));
		parts.set(0, new TextPart("changed"));
		assertEquals(List.of(new TextPart("original")), message.parts());
		assertThrows(UnsupportedOperationException.class, () -> message.parts().add(new TextPart("x")));

		List<SemanticMessage> messages = new ArrayList<>();
		messages.add(message);
		List<ToolSpec> tools = new ArrayList<>();
		tools.add(new ToolSpec("get_weather", "weather", "{}"));
		List<String> stop = new ArrayList<>();
		stop.add("STOP");
		SemanticRequest request = request(messages, tools, stop);
		messages.clear();
		tools.clear();
		stop.clear();
		assertEquals(List.of(message), request.messages());
		assertEquals(List.of(new ToolSpec("get_weather", "weather", "{}")), request.tools());
		assertEquals(List.of("STOP"), request.stop());
		assertThrows(UnsupportedOperationException.class, () -> request.messages().add(message));
		assertThrows(UnsupportedOperationException.class, () -> request.tools().add(new ToolSpec("t", null, null)));
		assertThrows(UnsupportedOperationException.class, () -> request.stop().add("X"));

		List<SemanticMessage> respMessages = new ArrayList<>();
		respMessages.add(message);
		SemanticResponse response = new SemanticResponse("id", respMessages, StopReason.COMPLETED,
				new Usage(null, null, null, null));
		respMessages.clear();
		assertEquals(List.of(message), response.messages());
		assertThrows(UnsupportedOperationException.class, () -> response.messages().add(message));
	}

	@Test
	void toolCallDeltaIncrementalFieldsAreNullable() {
		ToolCallDelta first = new ToolCallDelta(0, "call_1", "get_weather", null);
		assertEquals(0, first.index());
		assertEquals("call_1", first.id());
		assertEquals("get_weather", first.name());
		assertNull(first.arguments());

		ToolCallDelta later = new ToolCallDelta(0, null, null, "{\"city\":");
		assertNull(later.id());
		assertNull(later.name());
		assertEquals("{\"city\":", later.arguments());
		assertEquals(first, new ToolCallDelta(0, "call_1", "get_weather", null));
		assertNotEquals(first, later);
	}

}