package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.ContentPart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.StreamDecoder;
import io.github.leohou0430.llmgw.conversion.TerminalEvent;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ThinkingDelta;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.ToolCallDelta;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.Usage;
import io.github.leohou0430.llmgw.conversion.UsageEvent;
import io.github.leohou0430.llmgw.conversion.deepseek.DeepSeekChatCodec;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.13a: the controlled mock upstream must speak the REAL DeepSeek Chat wire format when
 * a {@code mock-deepseek*} marker is present, so the three entry protocols can be
 * accepted against the real codecs. Every emitted chunk is fed back through
 * {@link DeepSeekChatCodec} -- a frame that merely "looks like" DeepSeek but decodes to
 * nothing would not prove the wire contract. Pure JUnit: the mock is instantiated
 * directly, no Spring context.
 */
class MockUpstreamDeepSeekTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final DeepSeekChatCodec CODEC = new DeepSeekChatCodec();

	private static final String CHAT_PATH = "/mock/v1/chat/completions";

	private final HttpClient client = HttpClient.newHttpClient();

	private MockUpstreamServer mock;

	private int port;

	private void start(int chunks) throws IOException {
		GatewayProperties properties = new GatewayProperties();
		properties.getMock().setEnabled(true);
		properties.getMock().setChunks(chunks);
		properties.getMock().setDelayMs(0);
		properties.getMock().setChunkIntervalMs(0);
		// The OS can hand the just-released probe port to an outbound connection, so
		// retry the bind.
		for (int attempt = 0;; attempt++) {
			try (ServerSocket socket = new ServerSocket(0)) {
				port = socket.getLocalPort();
			}
			properties.getMock().setPort(port);
			try {
				mock = new MockUpstreamServer(properties);
				return;
			}
			catch (IllegalStateException e) {
				if (attempt >= 7) {
					throw e;
				}
			}
		}
	}

	@AfterEach
	void stop() {
		if (mock != null) {
			mock.destroy();
			mock = null;
		}
		client.close();
	}

	private static String request(String markers, boolean stream) {
		return "{\"model\":\"deepseek-flash\",\"stream\":" + stream
				+ ",\"messages\":[{\"role\":\"user\",\"content\":\"controlled acceptance " + markers + "\"}]}";
	}

	/**
	 * Reads the whole body; an injected transport crash surfaces as IOException, not a
	 * silent success.
	 */
	private String post(String body) throws IOException, InterruptedException {
		HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CHAT_PATH))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, response.statusCode());
		try (InputStream in = response.body()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * SSE payloads without the {@code data: } prefix; asserts the chat wire framing
	 * itself.
	 */
	private static List<String> payloads(String sse) {
		List<String> payloads = new ArrayList<>();
		for (String frame : sse.split("\n\n")) {
			if (frame.isEmpty()) {
				continue;
			}
			assertFalse(frame.contains("event:"), "DeepSeek chat SSE carries data lines only: " + frame);
			assertTrue(frame.startsWith("data: "), "every frame must be a data line: " + frame);
			payloads.add(frame.substring("data: ".length()));
		}
		return payloads;
	}

	private static List<String> chunks(String sse) {
		List<String> chunks = new ArrayList<>();
		for (String payload : payloads(sse)) {
			if (!"[DONE]".equals(payload)) {
				chunks.add(payload);
			}
		}
		return chunks;
	}

	private static List<SemanticEvent> decode(List<String> chunks, StreamDecoder decoder) {
		List<SemanticEvent> events = new ArrayList<>();
		for (String chunk : chunks) {
			events.addAll(decoder.onChunk(chunk));
		}
		return events;
	}

	private static String joinedText(List<SemanticEvent> events) {
		StringBuilder text = new StringBuilder();
		for (SemanticEvent event : events) {
			if (event instanceof TextDelta delta) {
				text.append(delta.text());
			}
		}
		return text.toString();
	}

	private static String joinedThinking(List<SemanticEvent> events) {
		StringBuilder text = new StringBuilder();
		for (SemanticEvent event : events) {
			if (event instanceof ThinkingDelta delta) {
				text.append(delta.reasoning());
			}
		}
		return text.toString();
	}

	private static String joinedArguments(List<SemanticEvent> events, int index) {
		StringBuilder text = new StringBuilder();
		for (SemanticEvent event : events) {
			if (event instanceof ToolCallDelta delta && delta.index() == index && delta.arguments() != null) {
				text.append(delta.arguments());
			}
		}
		return text.toString();
	}

	@Test
	void deepseekStreamEmitsWireExactChunksTheRealCodecDecodes() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek", true));

		assertTrue(sse.endsWith("data: [DONE]\n\n"), "stream must end with the [DONE] sentinel: " + sse);
		List<String> chunks = chunks(sse);
		assertEquals(6, chunks.size(), "one chunk per configured delta frame: " + sse);
		for (String chunk : chunks) {
			JsonNode root = MAPPER.readTree(chunk);
			assertTrue(root.isObject());
			assertEquals("chat.completion.chunk", root.path("object").asString());
			assertTrue(root.path("choices").isArray(), "chunk must carry choices: " + chunk);
		}
		JsonNode last = MAPPER.readTree(chunks.get(chunks.size() - 1));
		assertEquals("stop", last.path("choices").get(0).path("finish_reason").asString());
		assertTrue(last.path("usage").isObject(), "usage rides the final chunk: " + last);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertTrue(decoder.terminalSeen(), "finish_reason must reach the real decoder");
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertEquals("Hello world", joinedText(events));
		assertTrue(events.stream().filter(TextDelta.class::isInstance).count() >= 2,
				"text must arrive as incremental deltas: " + events);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 6L, null, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekThinkingStreamEmitsReasoningContentBeforeText() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek-thinking", true));
		assertTrue(sse.contains("\"reasoning_content\""), "thinking mode must use reasoning_content: " + sse);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks(sse), decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("Let me think", joinedThinking(events));
		assertEquals("Hello world", joinedText(events));
		assertTrue(events.stream().filter(ThinkingDelta.class::isInstance).count() >= 2,
				"reasoning must arrive as incremental deltas: " + events);
		assertTrue(lastIndexOf(events, ThinkingDelta.class) < firstIndexOf(events, TextDelta.class),
				"reasoning deltas must precede text deltas: " + events);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 6L, 2L, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekToolsStreamEmitsIndexedToolCallFragments() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek-tools", true));
		List<String> chunks = chunks(sse);

		JsonNode firstCall = null;
		int toolFrames = 0;
		for (String chunk : chunks) {
			JsonNode calls = MAPPER.readTree(chunk).path("choices").get(0).path("delta").path("tool_calls");
			if (!calls.isArray()) {
				continue;
			}
			toolFrames++;
			for (JsonNode call : calls) {
				assertTrue(call.path("index").isIntegralNumber(), "index is mandatory on every fragment: " + call);
				assertTrue(call.path("index").asInt() >= 0, "index must not be negative: " + call);
				if (firstCall == null) {
					firstCall = call;
				}
			}
		}
		assertTrue(toolFrames >= 3, "arguments must be split across frames: " + sse);
		assertEquals("call_mock_1", firstCall.path("id").asString());
		assertEquals("function", firstCall.path("type").asString());
		assertEquals("get_weather", firstCall.path("function").path("name").asString());
		assertEquals("", firstCall.path("function").path("arguments").asString(),
				"the opening fragment carries id/name and empty arguments");
		assertEquals("tool_calls",
				MAPPER.readTree(chunks.get(chunks.size() - 1)).path("choices").get(0).path("finish_reason").asString());

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("{\"city\":\"SF\"}", joinedArguments(events, 0));
		List<ToolCallDelta> deltas = events.stream()
			.filter(ToolCallDelta.class::isInstance)
			.map(ToolCallDelta.class::cast)
			.toList();
		assertEquals(new ToolCallDelta(0, "call_mock_1", "get_weather", ""), deltas.get(0));
		assertTrue(deltas.stream().skip(1).allMatch(d -> d.id() == null && d.name() == null),
				"later fragments carry index + arguments only: " + deltas);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 6L, null, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.TOOL_CALL)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekSingleChunkStillCarriesFinishReasonAndUsage() throws Exception {
		start(1);
		for (String marker : List.of("mock-deepseek", "mock-deepseek-thinking", "mock-deepseek-tools")) {
			String sse = post(request(marker, true));
			List<String> chunks = chunks(sse);
			assertEquals(1, chunks.size(), marker + ": " + sse);
			assertTrue(sse.endsWith("data: [DONE]\n\n"), marker + ": " + sse);

			StreamDecoder decoder = CODEC.newStreamDecoder();
			List<SemanticEvent> events = decode(chunks, decoder);
			assertFalse(decoder.failed(), marker + " failureReason=" + decoder.failureReason());
			assertTrue(decoder.terminalSeen(), marker);
			boolean thinking = marker.endsWith("thinking");
			boolean tools = marker.endsWith("tools");
			// A tool-call answer carries no text on the wire (content is null/absent).
			assertEquals(tools ? "" : "Hello world", joinedText(events), marker);
			assertEquals(List.of(new UsageEvent(new Usage(7L, 1L, thinking ? 2L : null, 3L))),
					events.stream().filter(UsageEvent.class::isInstance).toList(), marker);
			if (thinking) {
				assertEquals("Let me think", joinedThinking(events), marker);
			}
			if (tools) {
				assertEquals("{\"city\":\"SF\"}", joinedArguments(events, 0), marker);
				assertEquals(List.of(new TerminalEvent(StopReason.TOOL_CALL)),
						events.stream().filter(TerminalEvent.class::isInstance).toList(), marker);
			}
			else {
				assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
						events.stream().filter(TerminalEvent.class::isInstance).toList(), marker);
			}
		}
	}

	@Test
	void deepseekCorruptChunkFailsTheRealDecoderWithoutTerminal() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek mock-corrupt-chunk", true));
		List<String> chunks = chunks(sse);
		assertEquals(6, chunks.size(), sse);
		assertThrows(RuntimeException.class, () -> MAPPER.readTree(chunks.get(0)),
				"the injected frame must not be parseable JSON: " + chunks.get(0));
		assertTrue(sse.endsWith("data: [DONE]\n\n"),
				"transport still completes, so the error must be detected in-band: " + sse);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertTrue(decoder.failed(), "a corrupt chunk must fail the decoder");
		assertNotNull(decoder.failureReason());
		assertFalse(decoder.terminalSeen(), "no finish_reason was ever delivered");
		assertTrue(events.stream().noneMatch(TerminalEvent.class::isInstance),
				"a corrupt stream must not fabricate a success terminal: " + events);
		assertTrue(events.stream().noneMatch(UsageEvent.class::isInstance), events.toString());
	}

	@Test
	void deepseekCorruptChunkAtIndexKeepsEarlierDeltas() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek mock-corrupt-chunk-3", true));
		List<String> chunks = chunks(sse);
		assertEquals(6, chunks.size(), sse);
		for (int i = 0; i < 3; i++) {
			assertTrue(MAPPER.readTree(chunks.get(i)).path("choices").isArray(), "frame " + i + ": " + chunks.get(i));
		}
		assertThrows(RuntimeException.class, () -> MAPPER.readTree(chunks.get(3)),
				"frame 3 must be the corrupt one: " + chunks.get(3));

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertTrue(decoder.failed());
		assertFalse(decoder.terminalSeen());
		assertFalse(joinedText(events).isEmpty(), "deltas before the corrupt frame must survive: " + events);
		assertTrue(events.stream().noneMatch(TerminalEvent.class::isInstance), events.toString());
	}

	// ------------------------------- T1.13a review: small chunk counts + marker priority
	// + corrupt bounds

	@Test
	void deepseekToolsTwoChunksCarryFullArgumentsInOpeningFrame() throws Exception {
		start(2);
		String sse = post(request("mock-deepseek-tools", true));
		List<String> chunks = chunks(sse);
		assertEquals(2, chunks.size(), sse);

		// argumentFrames = 0: the opening frame carries the WHOLE arguments, the last
		// frame {} + finish + usage.
		JsonNode opening = MAPPER.readTree(chunks.get(0))
			.path("choices")
			.get(0)
			.path("delta")
			.path("tool_calls")
			.get(0);
		assertEquals("call_mock_1", opening.path("id").asString());
		assertEquals("get_weather", opening.path("function").path("name").asString());
		assertEquals("{\"city\":\"SF\"}", opening.path("function").path("arguments").asString());
		JsonNode last = MAPPER.readTree(chunks.get(1));
		assertEquals("tool_calls", last.path("choices").get(0).path("finish_reason").asString());
		assertTrue(last.path("usage").isObject(), "usage rides the final chunk: " + last);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("{\"city\":\"SF\"}", joinedArguments(events, 0));
		List<ToolCallDelta> deltas = events.stream()
			.filter(ToolCallDelta.class::isInstance)
			.map(ToolCallDelta.class::cast)
			.toList();
		assertEquals(List.of(new ToolCallDelta(0, "call_mock_1", "get_weather", "{\"city\":\"SF\"}")), deltas);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 2L, null, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.TOOL_CALL)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekToolsThreeChunksSplitArgumentsAcrossOneFrame() throws Exception {
		start(3);
		String sse = post(request("mock-deepseek-tools", true));
		List<String> chunks = chunks(sse);
		assertEquals(3, chunks.size(), sse);

		// argumentFrames = 1: opening carries name + empty args, frame 1 the whole args,
		// frame 2 finish + usage.
		JsonNode opening = MAPPER.readTree(chunks.get(0))
			.path("choices")
			.get(0)
			.path("delta")
			.path("tool_calls")
			.get(0);
		assertEquals("call_mock_1", opening.path("id").asString());
		assertEquals("get_weather", opening.path("function").path("name").asString());
		assertEquals("", opening.path("function").path("arguments").asString());
		JsonNode last = MAPPER.readTree(chunks.get(2));
		assertEquals("tool_calls", last.path("choices").get(0).path("finish_reason").asString());
		assertTrue(last.path("usage").isObject(), last.toString());

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("{\"city\":\"SF\"}", joinedArguments(events, 0));
		List<ToolCallDelta> deltas = events.stream()
			.filter(ToolCallDelta.class::isInstance)
			.map(ToolCallDelta.class::cast)
			.toList();
		assertEquals(List.of(new ToolCallDelta(0, "call_mock_1", "get_weather", ""),
				new ToolCallDelta(0, null, null, "{\"city\":\"SF\"}")), deltas);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 3L, null, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.TOOL_CALL)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekThinkingTwoChunksMergeReasoningAndContent() throws Exception {
		start(2);
		String sse = post(request("mock-deepseek-thinking", true));
		List<String> chunks = chunks(sse);
		assertEquals(2, chunks.size(), sse);

		// contentFrames = 1: a single merged frame carries reasoning + content, the last
		// frame {} + finish + usage.
		JsonNode merged = MAPPER.readTree(chunks.get(0)).path("choices").get(0).path("delta");
		assertEquals("Let me think", merged.path("reasoning_content").asString());
		assertEquals("Hello world", merged.path("content").asString());

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("Let me think", joinedThinking(events));
		assertEquals("Hello world", joinedText(events));
		assertTrue(lastIndexOf(events, ThinkingDelta.class) < firstIndexOf(events, TextDelta.class),
				"reasoning must precede text: " + events);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 2L, 2L, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekThinkingThreeChunksSplitReasoningThenContent() throws Exception {
		start(3);
		String sse = post(request("mock-deepseek-thinking", true));
		List<String> chunks = chunks(sse);
		assertEquals(3, chunks.size(), sse);

		// contentFrames = 2, reasoningFrames = max(1, 1) = 1: frame 0 reasoning, frame 1
		// content, frame 2 finish + usage.
		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		assertEquals("Let me think", joinedThinking(events));
		assertEquals("Hello world", joinedText(events));
		assertTrue(lastIndexOf(events, ThinkingDelta.class) < firstIndexOf(events, TextDelta.class),
				"reasoning must precede text: " + events);
		assertEquals(List.of(new UsageEvent(new Usage(7L, 3L, 2L, 3L))),
				events.stream().filter(UsageEvent.class::isInstance).toList());
		assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekThinkingAndToolsMarkersPreferTools() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek-thinking mock-deepseek-tools", true));
		List<String> chunks = chunks(sse);
		assertEquals(6, chunks.size(), sse);
		assertEquals("tool_calls",
				MAPPER.readTree(chunks.get(chunks.size() - 1)).path("choices").get(0).path("finish_reason").asString());

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertTrue(decoder.terminalSeen());
		// tools wins the branch: arguments reassemble, and neither reasoning nor text
		// deltas are emitted.
		assertEquals("{\"city\":\"SF\"}", joinedArguments(events, 0));
		assertTrue(events.stream().anyMatch(ToolCallDelta.class::isInstance), events.toString());
		assertTrue(events.stream().noneMatch(ThinkingDelta.class::isInstance),
				"tools branch must not emit reasoning even with the thinking marker: " + events);
		assertEquals("", joinedText(events), "tools branch carries no text: " + events);
		assertEquals(List.of(new TerminalEvent(StopReason.TOOL_CALL)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekCorruptLastFrameFailsWithoutTerminalOrUsage() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek mock-corrupt-chunk-5", true));
		List<String> chunks = chunks(sse);
		assertEquals(6, chunks.size(), sse);
		// frames 0..4 are legal text deltas; frame 5 (the finish + usage frame) is
		// replaced by illegal JSON.
		for (int i = 0; i < 5; i++) {
			assertTrue(MAPPER.readTree(chunks.get(i)).path("choices").isArray(), "frame " + i + ": " + chunks.get(i));
		}
		assertThrows(RuntimeException.class, () -> MAPPER.readTree(chunks.get(5)),
				"the final frame must be the corrupt one: " + chunks.get(5));
		assertTrue(sse.endsWith("data: [DONE]\n\n"), "transport still completes: " + sse);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertTrue(decoder.failed(), "a corrupt final frame must fail the decoder");
		assertNotNull(decoder.failureReason());
		assertFalse(decoder.terminalSeen(), "the finish_reason frame was destroyed");
		assertEquals("Hello world", joinedText(events), "every text delta precedes the corrupt frame: " + events);
		assertTrue(events.stream().noneMatch(TerminalEvent.class::isInstance), events.toString());
		assertTrue(events.stream().noneMatch(UsageEvent.class::isInstance), events.toString());
	}

	@Test
	void deepseekCorruptBeyondLastFrameStaysInert() throws Exception {
		start(4);
		// N >= chunks: the writeStream i-loop never reaches N, so nothing is injected
		// (same convention as fail/eof).
		for (String marker : List.of("mock-corrupt-chunk-4", "mock-corrupt-chunk-9")) {
			String sse = post(request("mock-deepseek " + marker, true));
			List<String> chunks = chunks(sse);
			assertEquals(4, chunks.size(), marker + ": " + sse);
			for (String chunk : chunks) {
				assertTrue(MAPPER.readTree(chunk).path("choices").isArray(), marker + ": " + chunk);
			}
			assertTrue(sse.endsWith("data: [DONE]\n\n"), marker + ": " + sse);

			StreamDecoder decoder = CODEC.newStreamDecoder();
			List<SemanticEvent> events = decode(chunks, decoder);
			assertFalse(decoder.failed(), marker + " failureReason=" + decoder.failureReason());
			assertTrue(decoder.terminalSeen(), marker);
			assertEquals("Hello world", joinedText(events), marker);
			assertEquals(List.of(new UsageEvent(new Usage(7L, 4L, null, 3L))),
					events.stream().filter(UsageEvent.class::isInstance).toList(), marker);
			assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
					events.stream().filter(TerminalEvent.class::isInstance).toList(), marker);
		}
	}

	private static int firstIndexOf(List<SemanticEvent> events, Class<?> type) {
		for (int i = 0; i < events.size(); i++) {
			if (type.isInstance(events.get(i))) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------- injected faults + legacy parity (T1.13a
	// slice 4)

	private record Received(String body, boolean broken) {
	}

	/**
	 * Keeps whatever arrived before an injected transport crash instead of losing it to
	 * the exception.
	 */
	private Received postCapturingBreak(String body) throws IOException, InterruptedException {
		HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CHAT_PATH))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, response.statusCode());
		StringBuilder received = new StringBuilder();
		boolean broken = false;
		try (InputStream in = response.body()) {
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
			}
		}
		catch (IOException e) {
			broken = true;
		}
		return new Received(received.toString(), broken);
	}

	@Test
	void deepseekEofAfterTruncatesWithoutTerminalUsageOrDone() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek mock-eof-after-2", true));
		assertFalse(sse.contains("[DONE]"), "clean EOF must not deliver the sentinel: " + sse);
		assertFalse(sse.contains("finish_reason\":\""), "no finish_reason may appear: " + sse);
		assertFalse(sse.contains("usage"), "no usage may appear: " + sse);
		List<String> chunks = chunks(sse);
		assertEquals(2, chunks.size(), sse);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks, decoder);
		assertFalse(decoder.terminalSeen(), "a silent truncation must stay incomplete");
		assertFalse(decoder.failed(), "clean EOF is not a codec failure");
		assertFalse(joinedText(events).isEmpty(), "partial deltas survive: " + events);
		assertTrue(events.stream().noneMatch(TerminalEvent.class::isInstance), events.toString());
	}

	@Test
	void deepseekZeroFrameEofEmitsNothingAtAll() throws Exception {
		start(6);
		String sse = post(request("mock-deepseek mock-eof-after-0", true));
		assertEquals("", sse, "zero-frame clean EOF must emit no bytes");
	}

	@Test
	void deepseekFailAfterBreaksTransportMidStream() throws Exception {
		start(6);
		Received received = postCapturingBreak(request("mock-deepseek mock-fail-after-2", true));
		assertTrue(received.broken(), "mock-fail-after must break the connection abnormally");
		assertFalse(received.body().contains("[DONE]"), received.body());
		assertFalse(received.body().contains("finish_reason\":\""), received.body());
		assertEquals(2, received.body().split("\n\n").length, received.body());
	}

	@Test
	void deepseekDoneThenFailStillDeliversTheTerminator() throws Exception {
		start(4);
		Received received = postCapturingBreak(request("mock-deepseek mock-done-then-fail", true));
		assertTrue(received.broken(), "the connection must break AFTER the terminator");
		assertTrue(received.body().endsWith("data: [DONE]\n\n"), received.body());

		StreamDecoder decoder = CODEC.newStreamDecoder();
		List<SemanticEvent> events = decode(chunks(received.body()), decoder);
		assertTrue(decoder.terminalSeen());
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
		assertEquals(List.of(new TerminalEvent(StopReason.COMPLETED)),
				events.stream().filter(TerminalEvent.class::isInstance).toList());
	}

	@Test
	void deepseekStallHeadersDelaysFirstByteButStillCompletes() throws Exception {
		start(2);
		long startNanos = System.nanoTime();
		String sse = post(request("mock-deepseek mock-stall-headers-200", true));
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
		assertTrue(elapsedMs >= 200, "first byte must be delayed by the marker, elapsed=" + elapsedMs + "ms");
		assertTrue(sse.endsWith("data: [DONE]\n\n"), sse);

		StreamDecoder decoder = CODEC.newStreamDecoder();
		decode(chunks(sse), decoder);
		assertTrue(decoder.terminalSeen());
		assertFalse(decoder.failed(), "failureReason=" + decoder.failureReason());
	}

	@Test
	void deepseekNonStreamCompletionDecodesWithTheRealCodec() throws Exception {
		start(6);
		for (String marker : List.of("mock-deepseek", "mock-deepseek-thinking", "mock-deepseek-tools")) {
			String json = post(request(marker, false));
			assertFalse(json.contains("data:"), "non-stream must be a single JSON body: " + json);
			JsonNode root = MAPPER.readTree(json);
			assertEquals("chat.completion", root.path("object").asString(), marker);
			JsonNode message = root.path("choices").get(0).path("message");
			assertEquals("assistant", message.path("role").asString(), marker);

			SemanticResponse response = CODEC.decodeResponse(json);
			assertEquals(1, response.messages().size(), marker);
			assertEquals(Role.ASSISTANT, response.messages().get(0).role(), marker);
			boolean thinking = marker.endsWith("thinking");
			boolean tools = marker.endsWith("tools");
			assertEquals(new Usage(7L, 1L, thinking ? 2L : null, 3L), response.usage(), marker);
			if (tools) {
				assertTrue(message.path("content").isNull(), "tool answers carry content:null: " + json);
				assertEquals(StopReason.TOOL_CALL, response.stop(), marker);
				assertEquals(List.of(new ToolCallPart("call_mock_1", "get_weather", "{\"city\":\"SF\"}")),
						response.messages().get(0).parts(), marker);
			}
			else {
				assertEquals(StopReason.COMPLETED, response.stop(), marker);
				List<ContentPart> expected = thinking
						? List.of(new ThinkingPart("Let me think", null), new TextPart("Hello world"))
						: List.of(new TextPart("Hello world"));
				assertEquals(expected, response.messages().get(0).parts(), marker);
			}
		}
	}

	@Test
	void legacyChatFramesStayByteIdenticalWithoutTheDeepseekMarker() throws Exception {
		start(4);
		StringBuilder expected = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			expected.append("data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",")
				.append("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok")
				.append(i)
				.append("\"}}]}\n\n");
		}
		expected.append("data: {\"usage\":{\"completion_tokens\":4}}\n\n").append("data: [DONE]\n\n");

		String legacy = post(request("", true));
		assertEquals(expected.toString(), legacy, "benchmark/smoke payloads must not change");
		assertEquals(legacy, post(request("mock-corrupt-chunk", true)),
				"the corrupt marker is deepseek-only and must stay inert for legacy payloads");
		assertEquals(legacy, post(request("mock-corrupt-chunk-2", true)),
				"the numbered corrupt marker must stay inert for legacy payloads");

		String legacyNonStream = post(request("", false));
		assertEquals(
				"{\"id\":\"mock\",\"object\":\"chat.completion\","
						+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
						+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},\"echoAuthStyle\":\"none\"}",
				legacyNonStream);

		String truncated = post(request("mock-eof-after-1", true));
		assertEquals("data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
				+ "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok0\"}}]}\n\n", truncated);
	}

	private static int lastIndexOf(List<SemanticEvent> events, Class<?> type) {
		for (int i = events.size() - 1; i >= 0; i--) {
			if (type.isInstance(events.get(i))) {
				return i;
			}
		}
		return -1;
	}

}