package io.github.leohou0430.llmgw.conversion.gemini;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gemini 编解码骨架合同：contents↔语义消息、candidates/usageMetadata、拒绝多模态。 */
class GeminiCodecTest {

	private final GeminiEntryCodec codec = new GeminiEntryCodec();

	private static final io.github.leohou0430.llmgw.conversion.ContinuationEnvelope ENVELOPE = new io.github.leohou0430.llmgw.conversion.ContinuationEnvelope(
			"gemini-codec-test".getBytes(java.nio.charset.StandardCharsets.UTF_8));

	private static CapabilityContext ctx() {
		// 骨架不消费 envelope/audit；按合同要求非 null。
		return new CapabilityContext("t1", "p1", new DegradationAudit(DegradationGrant.empty()), ENVELOPE);
	}

	@Test
	void decodeMapsContentsAndSystemInstruction() {
		String body = """
				{"systemInstruction":{"parts":[{"text":"be nice"}]},
				 "contents":[
				   {"role":"user","parts":[{"text":"hello"},{"text":" world"}]},
				   {"role":"model","parts":[{"text":"hi"}]}],
				 "generationConfig":{"maxOutputTokens":256,"temperature":0.5}}""";
		SemanticRequest req = codec.decode(body, ctx());
		assertEquals(3, req.messages().size());
		assertEquals(Role.SYSTEM, req.messages().get(0).role());
		assertEquals("be nice", ((TextPart) req.messages().get(0).parts().get(0)).text());
		assertEquals(Role.USER, req.messages().get(1).role());
		assertEquals("hello world", ((TextPart) req.messages().get(1).parts().get(0)).text());
		assertEquals(Role.ASSISTANT, req.messages().get(2).role());
		assertEquals(256, req.maxOutputTokens());
		assertEquals(0.5, req.temperature());
	}

	@Test
	void inlineImageMapsToImagePartWithWhitelistValidation() {
		String body = """
				{"contents":[{"role":"user","parts":[
				  {"text":"what is this?"},
				  {"inlineData":{"mimeType":"image/png","data":"aGVsbG8="}}]}]}""";
		SemanticRequest req = codec.decode(body, ctx());
		var parts = req.messages().get(0).parts();
		assertEquals(2, parts.size(), "文本与图片共存");
		assertTrue(parts.get(1) instanceof ImagePart);
		assertEquals("image/png", ((ImagePart) parts.get(1)).mediaType());

		String badType = """
				{"contents":[{"role":"user","parts":[
				  {"inlineData":{"mimeType":"image/bmp","data":"aGVsbG8="}}]}]}""";
		assertThrows(CapabilityException.class, () -> codec.decode(badType, ctx()), "非白名单媒体类型拒绝");

		String badBase64 = """
				{"contents":[{"role":"user","parts":[
				  {"inlineData":{"mimeType":"image/png","data":"not!base64!"}}]}]}""";
		assertThrows(CapabilityException.class, () -> codec.decode(badBase64, ctx()), "损坏 base64 拒绝");
	}

	@Test
	void nonTextPartsAreRejectedNotFabricated() {
		String body = """
				{"contents":[{"role":"user","parts":[{"audioData":{"mimeType":"audio/wav"}}]}]}""";
		assertThrows(CapabilityException.class, () -> codec.decode(body, ctx()), "不支持的载荷仍显式拒绝");
	}

	@Test
	void functionResponseMapsToToolResultPart() {
		String body = """
				{"contents":[
				  {"role":"user","parts":[{"functionResponse":{"name":"lookup",
				    "response":{"result":"found"}}}]}]}""";
		SemanticRequest req = codec.decode(body, ctx());
		assertEquals(1, req.messages().size());
		assertTrue(
				req.messages().get(0).parts().get(0) instanceof io.github.leohou0430.llmgw.conversion.ToolResultPart);
		var result = (io.github.leohou0430.llmgw.conversion.ToolResultPart) req.messages().get(0).parts().get(0);
		assertEquals("lookup", result.toolCallId(), "Gemini id=name 约定");
		assertTrue(result.content().contains("found"));
	}

	@Test
	void functionCallPartMapsToToolCallPartAndBackToGeminiShape() {
		String body = """
				{"contents":[{"role":"model","parts":[
				  {"functionCall":{"name":"lookup","args":{"q":"llmgw"}}}]}]}""";
		SemanticRequest req = codec.decode(body, ctx());
		var call = (io.github.leohou0430.llmgw.conversion.ToolCallPart) req.messages().get(0).parts().get(0);
		assertEquals("lookup", call.name());
		assertTrue(call.argumentsJson().contains("llmgw"));

		// 编码回 Gemini 形状：candidates.parts.functionCall。
		var resp = new SemanticResponse("id", List.of(new SemanticMessage(Role.ASSISTANT, List.of(call))),
				StopReason.TOOL_CALL, null);
		String json = codec.encodeResponse(resp, "m", ctx());
		assertTrue(json.contains("\"functionCall\""));
		assertTrue(json.contains("\"name\":\"lookup\""));
		assertTrue(json.contains("\"finishReason\":\"STOP\""));
	}

	@Test
	void encodeResponseRendersCandidatesAndUsageMetadata() {
		var resp = new SemanticResponse("id-1",
				List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("answer")))), StopReason.COMPLETED,
				new Usage(10L, 5L, null, null));
		String json = codec.encodeResponse(resp, "gemini-2.0-flash", ctx());
		assertTrue(json.contains("\"candidates\""));
		assertTrue(json.contains("\"text\":\"answer\""));
		assertTrue(json.contains("\"finishReason\":\"STOP\""));
		assertTrue(json.contains("\"promptTokenCount\":10"));
		assertTrue(json.contains("\"candidatesTokenCount\":5"));
	}

	@Test
	void errorShapeIsGeminiStyled() {
		var json = codec.errorJson(new CapabilityException("invalid_request", 400, "body", "bad request shape"));
		assertTrue(json.contains("\"error\""));
		assertTrue(json.contains("\"status\":\"INVALID_ARGUMENT\""));
	}

	@Test
	void streamEncoderEmitsIncrementThenTerminalOnce() {
		var encoder = codec.newStreamEncoder("gemini-2.0-flash", true, ctx());
		var first = encoder.onEvent(new io.github.leohou0430.llmgw.conversion.TextDelta("hi"));
		assertEquals(1, first.size());
		assertTrue(first.get(0).startsWith("data: "));
		var terminal = encoder.onEvent(new io.github.leohou0430.llmgw.conversion.TerminalEvent(StopReason.COMPLETED));
		assertEquals(1, terminal.size());
		assertTrue(encoder.terminalSeen());
		assertTrue(encoder.onEvent(new io.github.leohou0430.llmgw.conversion.TextDelta("late")).isEmpty(), "终态后事件忽略");
		assertTrue(encoder.onComplete().isEmpty(), "Gemini 不发 [DONE] 哨兵");
	}

}
