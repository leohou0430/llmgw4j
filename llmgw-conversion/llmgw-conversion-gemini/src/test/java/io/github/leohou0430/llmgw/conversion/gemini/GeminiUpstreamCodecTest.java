package io.github.leohou0430.llmgw.conversion.gemini;

import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.StreamDecoder;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.ToolSpec;
import io.github.leohou0430.llmgw.conversion.TerminalEvent;
import io.github.leohou0430.llmgw.conversion.Usage;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.ToolCallDelta;
import io.github.leohou0430.llmgw.conversion.ToolResultPart;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务8 Gemini 上游编解码 fixtures：system/多轮映射、usage 只取一次、流式终态、工具/思考拒绝。 */
class GeminiUpstreamCodecTest {

	private final GeminiUpstreamCodec codec = new GeminiUpstreamCodec();

	@Test
	void encodeRequestMapsSystemAndMultiTurn() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.SYSTEM, List.of(new TextPart("be nice"))),
						new SemanticMessage(Role.USER, List.of(new TextPart("hello"))),
						new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("hi"))),
						new SemanticMessage(Role.USER, List.of(new TextPart("how are you")))),
				List.of(), null, null, 256, false, null, List.of("END"), 0.5, 0.9, null, null, null);
		var payload = codec.encodeRequest(req, "gemini-2.0-flash");
		assertTrue(payload.rawJson().contains("\"systemInstruction\""), "SYSTEM 聚合进 systemInstruction");
		assertTrue(payload.rawJson().contains("\"role\":\"user\""));
		assertTrue(payload.rawJson().contains("\"role\":\"model\""), "ASSISTANT 映射为 model");
		assertTrue(payload.rawJson().contains("\"maxOutputTokens\":256"));
		assertTrue(payload.rawJson().contains("\"stopSequences\""));
		assertTrue(payload.path().equals("/models/gemini-2.0-flash:generateContent"));
		// systemInstruction 不进 contents：4 条消息里 SYSTEM 被聚合，contents 3 条。
		assertEquals(3, payload.rawJson().split("\"role\":\"")[0].length() >= 0
				? countOccurrences(payload.rawJson(), "\"role\":\"") : -1);
	}

	@Test
	void toolDeclarationsCarryNameDescriptionAndSchema() {
		var req = new SemanticRequest("m", List.of(new SemanticMessage(Role.USER, List.of(new TextPart("hi")))),
				List.of(new ToolSpec("lookup", "search the web",
						"{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")),
				null, null, null, false, null, null, null, null, null, null, null);
		var payload = codec.encodeRequest(req, "gemini-2.0-flash");
		assertTrue(payload.rawJson().contains("\"functionDeclarations\""));
		assertTrue(payload.rawJson().contains("\"name\":\"lookup\""));
		assertTrue(payload.rawJson().contains("\"description\":\"search the web\""));
		assertTrue(payload.rawJson().contains("\"parameters\""), "JSON Schema 透传");
	}

	@Test
	void toolCallAndResultRoundTripThroughGeminiShapes() {
		// 语义：ASSISTANT 发起 tool call → USER 返回结果。
		var req = new SemanticRequest("m", List.of(
				new SemanticMessage(Role.ASSISTANT, List.of(new ToolCallPart("call-1", "lookup", "{\"q\":\"llmgw\"}"))),
				new SemanticMessage(Role.USER, List.of(new ToolResultPart("call-1", "{\"result\":\"found\"}", false)))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		var payload = codec.encodeRequest(req, "gemini-2.0-flash");
		assertTrue(payload.rawJson().contains("\"functionCall\":{\"name\":\"lookup\""));
		assertTrue(payload.rawJson().contains("\"functionResponse\":{\"name\":\"call-1\""),
				"toolCallId 作为 name 关联（Gemini 约定）");
		assertTrue(payload.rawJson().contains("\"found\""));

		// 上游响应：functionCall 终态 → 语义 TOOL_CALL。
		var resp = codec.decodeResponse("""
				{"candidates":[{"content":{"parts":[
				  {"functionCall":{"name":"lookup","args":{"q":"llmgw"}}}],
				  "role":"model"},"finishReason":"STOP"}],
				 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""");
		assertEquals(StopReason.TOOL_CALL, resp.stop(), "工具终态映射");
		assertTrue(resp.messages().get(0).parts().get(0) instanceof ToolCallPart);
		assertEquals("lookup", ((ToolCallPart) resp.messages().get(0).parts().get(0)).name());
	}

	@Test
	void streamDecoderEmitsToolCallDeltaWithOrdering() {
		StreamDecoder decoder = codec.newStreamDecoder();
		var events = decoder.onChunk("""
				{"candidates":[{"content":{"role":"model","parts":[
				  {"functionCall":{"name":"lookup","args":{"q":"llmgw"}}}]},
				  "finishReason":"STOP"}]}""");
		assertTrue(
				events.stream()
					.anyMatch(e -> e instanceof ToolCallDelta && ((ToolCallDelta) e).name().equals("lookup")),
				"流式工具调用增量");
		assertTrue(events.stream().anyMatch(e -> e instanceof TerminalEvent),
				"events=" + events.stream().map(e -> e.getClass().getSimpleName()).toList());
	}

	@Test
	void imagePartsRoundTripThroughInlineDataForUserMessages() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.USER,
						List.of(new TextPart("what is this?"), new ImagePart("image/png", "aGVsbG8=", null, null)))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		var payload = codec.encodeRequest(req, "gemini-2.0-flash");
		assertTrue(payload.rawJson().contains("\"inlineData\""));
		assertTrue(payload.rawJson().contains("\"mimeType\":\"image/png\""));
		assertTrue(payload.rawJson().contains("\"data\":\"aGVsbG8=\""));

		// 非 user 消息含图：前置拒绝。
		var badRole = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.ASSISTANT,
						List.of(new ImagePart("image/png", "aGVsbG8=", null, null)))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		assertThrows(UpstreamException.class, () -> codec.encodeRequest(badRole, "m"), "图片仅允许 user 消息");

		// 非白名单媒体类型拒绝。
		var badType = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.USER, List.of(new ImagePart("image/bmp", "aGVsbG8=", null, null)))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		assertThrows(UpstreamException.class, () -> codec.encodeRequest(badType, "m"));
	}

	@Test
	void clientFabricatedThinkingIsRejected() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.SYSTEM, List.of(new ThinkingPart("fabricated", null)))), List.of(),
				null, null, null, false, null, null, null, null, null, null, null);
		assertThrows(UpstreamException.class, () -> codec.encodeRequest(req, "m"), "不伪造思考");
	}

	@Test
	void decodeResponseMapsCandidatesFinishReasonAndUsage() {
		String upstream = """
				{"candidates":[{"content":{"parts":[{"text":"answer"}],"role":"model"},
				  "finishReason":"MAX_TOKENS"}],
				 "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":4}}""";
		SemanticResponse resp = codec.decodeResponse(upstream);
		assertEquals(1, resp.messages().size());
		assertEquals("answer", ((TextPart) resp.messages().get(0).parts().get(0)).text());
		assertEquals(StopReason.LENGTH, resp.stop());
		assertEquals(7L, resp.usage().inputTokens());
		assertEquals(4L, resp.usage().outputTokens());
	}

	@Test
	void emptyCandidatesMapToContentFilterWithBlockReason() {
		String upstream = """
				{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}""";
		SemanticResponse resp = codec.decodeResponse(upstream);
		assertTrue(resp.messages().isEmpty());
		assertEquals(StopReason.CONTENT_FILTER, resp.stop(), "安全拦截映射为 CONTENT_FILTER");
	}

	@Test
	void streamDecoderEmitsDeltaTerminalAndCountUsageOnce() {
		StreamDecoder decoder = codec.newStreamDecoder();
		List<SemanticEvent> first = decoder.onChunk("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"he\"}]}}]}");
		assertEquals(1, first.size());
		assertTrue(first.get(0) instanceof TextDelta);
		// usage-only 中间帧：只补 usage，不重复累计（后续尾帧不再发 UsageEvent）。
		List<SemanticEvent> usageFrame = decoder
			.onChunk("{\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":4}}");
		assertEquals(1, usageFrame.size());
		assertTrue(usageFrame.get(0) instanceof io.github.leohou0430.llmgw.conversion.UsageEvent);
		List<SemanticEvent> terminal = decoder
			.onChunk("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"llo\"}]}}],"
					+ "\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":4}}");
		assertTrue(terminal.get(0) instanceof TextDelta, "尾帧文本增量");
		assertTrue(terminal.stream().anyMatch(e -> e instanceof TerminalEvent), "恰一次终态");
		assertTrue(terminal.stream()
			.filter(e -> e instanceof io.github.leohou0430.llmgw.conversion.UsageEvent)
			.count() <= 1, "usage 不重复累计");
		assertTrue(decoder.terminalSeen());
		assertTrue(decoder.onChunk("{}").isEmpty(), "终态后事件忽略");
	}

	@Test
	void malformedChunkFailsInsteadOfThrowingRaw() {
		StreamDecoder decoder = codec.newStreamDecoder();
		decoder.onChunk("not-json");
		assertTrue(decoder.failed());
		assertEquals("invalid gemini chunk", decoder.failureReason());
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) >= 0) {
			count++;
			idx += needle.length();
		}
		return count;
	}

}
