package io.github.leohou0430.llmgw.conversion.openaichat;

import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.StreamDecoder;
import io.github.leohou0430.llmgw.conversion.TerminalEvent;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.ToolSpec;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务8 通用 Chat 上游编解码 fixtures：messages 映射、usage 只取一次、流式终态。 */
class ChatUpstreamCodecTest {

	private final ChatUpstreamCodec codec = new ChatUpstreamCodec();

	@Test
	void encodeRequestMapsRolesAndSampling() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.SYSTEM, List.of(new TextPart("be nice"))),
						new SemanticMessage(Role.USER, List.of(new TextPart("hello")))),
				List.of(), null, null, 128, false, null, List.of("END"), 0.7, 0.95, null, null, null);
		var payload = codec.encodeRequest(req, "gpt-any");
		assertTrue(payload.rawJson().contains("\"role\":\"system\""));
		assertTrue(payload.rawJson().contains("\"role\":\"user\""));
		assertTrue(payload.rawJson().contains("\"max_tokens\":128"));
		assertTrue(payload.rawJson().contains("\"temperature\":0.7"));
		assertTrue(payload.rawJson().contains("\"top_p\":0.95"));
		assertTrue(payload.rawJson().contains("\"stop\""));
		assertEquals("/chat/completions", payload.path());
	}

	@Test
	void toolCallAndResultRoundTripThroughChatShapes() {
		var req = new SemanticRequest("m", List.of(
				new SemanticMessage(Role.ASSISTANT, List.of(new ToolCallPart("call_1", "lookup", "{\"q\":\"llmgw\"}"))),
				new SemanticMessage(Role.TOOL,
						List.of(new io.github.leohou0430.llmgw.conversion.ToolResultPart("call_1",
								"{\"result\":\"found\"}", false)))),
				List.of(new ToolSpec("lookup", "search", "{\"type\":\"object\"}")), null, null, null, false, null, null,
				null, null, null, null, null);
		var payload = codec.encodeRequest(req, "gpt-any");
		assertTrue(payload.rawJson().contains("\"tools\""), "工具声明");
		assertTrue(payload.rawJson().contains("\"tool_calls\""), "assistant 携带 tool_calls");
		assertTrue(payload.rawJson().contains("\"id\":\"call_1\""));
		assertTrue(payload.rawJson().contains("\"role\":\"tool\""), "结果走 tool 角色");

		// 上游响应：tool_calls + finish_reason=tool_calls → 语义 TOOL_CALL。
		var resp = codec.decodeResponse("""
				{"id":"c1","choices":[{"message":{"role":"assistant","content":null,
				  "tool_calls":[{"id":"call_1","type":"function",
				    "function":{"name":"lookup","arguments":"{\\"q\\":\\"llmgw\\"}"}}]},
				  "finish_reason":"tool_calls"}],
				 "usage":{"prompt_tokens":5,"completion_tokens":1}}""");
		assertEquals(StopReason.TOOL_CALL, resp.stop());
		assertTrue(resp.messages().get(0).parts().get(0) instanceof io.github.leohou0430.llmgw.conversion.ToolCallPart);
	}

	@Test
	void streamDecoderEmitsToolCallDeltaFragments() {
		StreamDecoder decoder = codec.newStreamDecoder();
		var events = decoder.onChunk("""
				{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1",
				  "function":{"name":"lookup","arguments":""}}]}}]}""");
		assertTrue(
				events.stream()
					.anyMatch(e -> e instanceof io.github.leohou0430.llmgw.conversion.ToolCallDelta
							&& "call_1".equals(((io.github.leohou0430.llmgw.conversion.ToolCallDelta) e).id())),
				"流式工具增量携带 id");
		var terminal = decoder.onChunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
		assertTrue(terminal.stream()
			.anyMatch(e -> e instanceof TerminalEvent && ((TerminalEvent) e).stop() == StopReason.TOOL_CALL));
	}

	@Test
	void imagePartsRoundTripThroughDataUrlsForUserMessages() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.USER,
						List.of(new TextPart("what is this?"), new ImagePart("image/jpeg", "aGVsbG8=", null, "high")))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		var payload = codec.encodeRequest(req, "gpt-any");
		assertTrue(payload.rawJson().contains("\"image_url\""));
		assertTrue(payload.rawJson().contains("data:image/jpeg;base64,aGVsbG8="));
		assertTrue(payload.rawJson().contains("\"detail\":\"high\""));

		var badType = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.USER, List.of(new ImagePart("image/tiff", "aGVsbG8=", null, null)))),
				List.of(), null, null, null, false, null, null, null, null, null, null, null);
		assertThrows(io.github.leohou0430.llmgw.conversion.UpstreamException.class,
				() -> codec.encodeRequest(badType, "m"), "非白名单媒体类型拒绝");
	}

	@Test
	void clientFabricatedThinkingIsRejected() {
		var req = new SemanticRequest("m",
				List.of(new SemanticMessage(Role.ASSISTANT, List.of(new ThinkingPart("fabricated", null)))), List.of(),
				null, null, null, false, null, null, null, null, null, null, null);
		assertThrows(io.github.leohou0430.llmgw.conversion.UpstreamException.class, () -> codec.encodeRequest(req, "m"),
				"不伪造思考");
	}

	@Test
	void decodeResponseMapsChoicesFinishReasonAndUsage() {
		String upstream = """
				{"id":"cmpl-1","choices":[{"message":{"role":"assistant","content":"answer"},
				  "finish_reason":"stop"}],
				 "usage":{"prompt_tokens":11,"completion_tokens":5,"cached_tokens":2}}""";
		SemanticResponse resp = codec.decodeResponse(upstream);
		assertEquals("answer", ((TextPart) resp.messages().get(0).parts().get(0)).text());
		assertEquals(StopReason.COMPLETED, resp.stop());
		assertEquals(11L, resp.usage().inputTokens());
		assertEquals(5L, resp.usage().outputTokens());
		assertEquals(2L, resp.usage().cachedInputTokens());
	}

	@Test
	void missingChoicesIsAnUpstreamErrorNotAFabricatedSuccess() {
		assertThrows(io.github.leohou0430.llmgw.conversion.UpstreamException.class,
				() -> codec.decodeResponse("{\"choices\":[]}"), "缺 choices 不伪造成功");
	}

	@Test
	void streamDecoderEmitsDeltaTerminalAndCountsUsageOnce() {
		StreamDecoder decoder = codec.newStreamDecoder();
		List<SemanticEvent> first = decoder.onChunk("{\"choices\":[{\"delta\":{\"content\":\"he\"}}]}");
		assertTrue(first.get(0) instanceof TextDelta);
		List<SemanticEvent> usageFrame = decoder
			.onChunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}");
		assertEquals(1, usageFrame.size());
		assertTrue(usageFrame.get(0) instanceof io.github.leohou0430.llmgw.conversion.UsageEvent);
		List<SemanticEvent> terminal = decoder
			.onChunk("{\"choices\":[{\"delta\":{\"content\":\"llo\"},\"finish_reason\":\"stop\"}],"
					+ "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}");
		assertTrue(terminal.stream().anyMatch(e -> e instanceof TerminalEvent));
		assertTrue(terminal.stream()
			.filter(e -> e instanceof io.github.leohou0430.llmgw.conversion.UsageEvent)
			.count() <= 1, "usage 不重复累计");
		assertTrue(decoder.terminalSeen());
	}

}
