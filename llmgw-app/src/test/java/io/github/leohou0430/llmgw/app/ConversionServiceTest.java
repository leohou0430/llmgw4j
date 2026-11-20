package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.11 orchestration against the REAL codecs, discovered by name through the conversion
 * module. All upstream traffic is a controlled literal fixture: no network, no mock
 * upstream process.
 */
class ConversionServiceTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final String CALLER_MODEL = "client-model";

	private static final String UPSTREAM_MODEL = "deepseek-chat";

	private static final String USAGE = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";

	/** Controlled DeepSeek Chat non-streaming completion. */
	private static final String UPSTREAM_RESPONSE = """
			{"id":"chatcmpl-up","object":"chat.completion","created":1700000000,"model":"deepseek-chat",
			 "choices":[{"index":0,"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],
			 "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""";

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

	private static Route route(String targetModel) {
		return new Route("r", "*", CALLER_MODEL, targetModel, "https://api.deepseek.com", "sk-test", "", 1, List.of(),
				"chat");
	}

	private static ParsedRequest parsed(ProtocolKind kind, String json, boolean stream) {
		return new ParsedRequest(json, kind, CALLER_MODEL, stream, "hi", 1, 1, null);
	}

	private static String chatRequest(boolean stream) {
		return "{\"model\":\"" + CALLER_MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"
				+ (stream ? ",\"stream\":true,\"stream_options\":{\"include_usage\":true}" : "") + "}";
	}

	/** Upstream chat.completion.chunk fixture; finishReason null = still streaming. */
	private static String chunk(String deltaJson, String finishReason, String usageJson) {
		return "{\"id\":\"chatcmpl-up\",\"object\":\"chat.completion.chunk\",\"created\":1700000000," + "\"model\":\""
				+ UPSTREAM_MODEL + "\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson + ",\"finish_reason\":"
				+ (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]"
				+ (usageJson == null ? "" : ",\"usage\":" + usageJson) + "}";
	}

	private static String data(String payload) {
		return "data: " + payload;
	}

	private static long count(List<String> lines, String needle) {
		return lines.stream().filter(line -> line.contains(needle)).count();
	}

	private static String joined(List<String> lines) {
		return String.join("\n", lines);
	}

	// ---------- non-streaming ----------

	@Test
	void chatRequestBecomesADeepSeekChatPayloadAndTheResponseReturnsToTheCallerProtocol() {
		try (var context = context()) {
			ConversionService service = service(context);
			try (PreparedConversion prepared = service.prepare(parsed(ProtocolKind.CHAT, chatRequest(false), false),
					route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty())) {
				assertEquals("/chat/completions", prepared.upstreamPath());
				JsonNode upstream = MAPPER.readTree(prepared.upstreamJson());
				assertFalse(prepared.upstreamJson().isBlank());
				assertEquals(UPSTREAM_MODEL, upstream.path("model").asString());
				assertEquals("hi", upstream.path("messages").get(0).path("content").asString());
				assertFalse(upstream.path("stream").asBoolean());

				JsonNode entry = MAPPER.readTree(prepared.transcodeResponse(UPSTREAM_RESPONSE));
				assertEquals("hello there", entry.path("choices").get(0).path("message").path("content").asString());
				assertEquals("stop", entry.path("choices").get(0).path("finish_reason").asString());
				// The caller model, never the rewritten upstream model.
				assertEquals(CALLER_MODEL, entry.path("model").asString());
				assertEquals("chat.completion", entry.path("object").asString());
				assertEquals(5, entry.path("usage").path("total_tokens").asInt());
			}
		}
	}

	@Test
	void responsesRequestIsConvertedAndTheResponseUsesTheResponsesOutputShape() {
		try (var context = context()) {
			ConversionService service = service(context);
			String request = "{\"model\":\"" + CALLER_MODEL + "\",\"input\":\"hi\"}";
			try (PreparedConversion prepared = service.prepare(parsed(ProtocolKind.RESPONSES, request, false),
					route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty())) {
				assertEquals("/chat/completions", prepared.upstreamPath());
				assertEquals("hi",
						MAPPER.readTree(prepared.upstreamJson()).path("messages").get(0).path("content").asString());

				JsonNode entry = MAPPER.readTree(prepared.transcodeResponse(UPSTREAM_RESPONSE));
				assertEquals("response", entry.path("object").asString());
				assertEquals("completed", entry.path("status").asString());
				assertEquals(CALLER_MODEL, entry.path("model").asString());
				assertEquals("hello there", entry.path("output").get(0).path("content").get(0).path("text").asString());
				assertEquals("output_text", entry.path("output").get(0).path("content").get(0).path("type").asString());
			}
		}
	}

	@Test
	void messagesRequestIsConvertedAndTheResponseUsesTheMessagesContentBlocks() {
		try (var context = context()) {
			ConversionService service = service(context);
			String request = "{\"model\":\"" + CALLER_MODEL + "\",\"max_tokens\":1024,"
					+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
			try (PreparedConversion prepared = service.prepare(parsed(ProtocolKind.MESSAGES, request, false),
					route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty())) {
				assertEquals("/chat/completions", prepared.upstreamPath());
				JsonNode upstream = MAPPER.readTree(prepared.upstreamJson());
				assertEquals(1024, upstream.path("max_tokens").asInt());
				assertEquals("hi", upstream.path("messages").get(0).path("content").asString());

				JsonNode entry = MAPPER.readTree(prepared.transcodeResponse(UPSTREAM_RESPONSE));
				assertEquals("message", entry.path("type").asString());
				assertEquals("assistant", entry.path("role").asString());
				assertEquals("end_turn", entry.path("stop_reason").asString());
				assertEquals(CALLER_MODEL, entry.path("model").asString());
				assertEquals("text", entry.path("content").get(0).path("type").asString());
				assertEquals("hello there", entry.path("content").get(0).path("text").asString());
			}
		}
	}

	@Test
	void aRouteWithoutATargetModelKeepsTheSemanticModel() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(false), false), route(""), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				assertEquals(CALLER_MODEL, MAPPER.readTree(prepared.upstreamJson()).path("model").asString());
			}
		}
	}

	@Test
	void anUnsupportedEntryProtocolFailsWith501InsteadOfSilentlyPassingThrough() {
		try (var context = context()) {
			ConversionService service = service(context);
			CapabilityException failure = assertThrows(CapabilityException.class,
					() -> service.prepare(parsed(ProtocolKind.IMAGES, "{\"model\":\"m\"}", false),
							route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty()));
			assertEquals(501, failure.status());
			assertEquals(CapabilityException.NOT_SUPPORTED, failure.violations().get(0).code());
		}
	}

	@Test
	void theDegradationGrantReachesTheEntryCodecThroughThePerRequestContext() {
		try (var context = context()) {
			ConversionService service = service(context);
			String request = "{\"model\":\"" + CALLER_MODEL + "\",\"tool_choice\":\"required\","
					+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
			// Unauthorized: the codec's capability guard must reject, not silently
			// degrade.
			CapabilityException denied = assertThrows(CapabilityException.class,
					() -> service.prepare(parsed(ProtocolKind.CHAT, request, false), route(UPSTREAM_MODEL), "tenant-1",
							"project-1", DegradationGrant.empty()));
			assertEquals(400, denied.status());
			assertEquals(CapabilityException.DEGRADATION_NOT_AUTHORIZED, denied.violations().get(0).code());
			// Authorized: the same request degrades to auto and converts.
			try (PreparedConversion prepared = service.prepare(parsed(ProtocolKind.CHAT, request, false),
					route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.parse("tool_choice.force_to_auto"))) {
				assertEquals("auto", MAPPER.readTree(prepared.upstreamJson()).path("tool_choice").asString());
			}
		}
	}

	// ---------- streaming ----------

	@Test
	void chatStreamEmitsOneTerminalAndExactlyOneDone() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				JsonNode upstream = MAPPER.readTree(prepared.upstreamJson());
				assertTrue(upstream.path("stream").asBoolean());
				assertTrue(upstream.path("stream_options").path("include_usage").asBoolean());

				var lines = new ArrayList<String>();
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					// Framing noise carries no semantics.
					assertEquals(List.of(), transcoder.onUpstreamLine(null));
					assertEquals(List.of(), transcoder.onUpstreamLine(""));
					assertEquals(List.of(), transcoder.onUpstreamLine("   "));
					assertEquals(List.of(), transcoder.onUpstreamLine(": keep-alive"));
					assertEquals(List.of(), transcoder.onUpstreamLine("event: ping"));
					assertFalse(transcoder.terminalSeen());

					lines.addAll(transcoder
						.onUpstreamLine(data(chunk("{\"role\":\"assistant\",\"content\":\"Hel\"}", null, null))));
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"lo\"}", null, null))));
					assertFalse(transcoder.terminalSeen());
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{}", "stop", USAGE))));
					assertTrue(transcoder.terminalSeen());
					lines.addAll(transcoder.onUpstreamLine(data("[DONE]")));
					// Repeated end signals never duplicate the terminator.
					assertEquals(List.of(), transcoder.onUpstreamComplete());
				}

				assertEquals(1, count(lines, "data: [DONE]"), joined(lines));
				assertEquals(1, count(lines, "\"finish_reason\":\"stop\""), joined(lines));
				assertTrue(lines.get(0).contains("\"role\":\"assistant\""), joined(lines));
				assertTrue(lines.get(0).contains("\"content\":\"Hel\""), joined(lines));
				assertTrue(lines.get(1).contains("\"content\":\"lo\""), joined(lines));
				// include_usage restores an independent usage-only chunk AFTER the
				// terminal frame.
				String usageChunk = lines.get(lines.size() - 2);
				assertTrue(usageChunk.contains("\"usage\""), joined(lines));
				assertTrue(usageChunk.contains("\"choices\":[]"), joined(lines));
				assertEquals("data: [DONE]", lines.get(lines.size() - 1));
				// Every entry frame carries the caller model, not the upstream one.
				for (String line : lines) {
					if (line.contains("\"model\"")) {
						assertTrue(line.contains("\"model\":\"" + CALLER_MODEL + "\""), line);
						assertFalse(line.contains(UPSTREAM_MODEL), line);
					}
				}
			}
		}
	}

	@Test
	void upstreamEndWithoutADoneMarkerStillCompletesTheEntryStreamOnce() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				var lines = new ArrayList<String>();
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"hi\"}", null, null))));
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{}", "stop", USAGE))));
					lines.addAll(transcoder.onUpstreamComplete());
					assertEquals(List.of(), transcoder.onUpstreamComplete());
				}
				assertEquals(1, count(lines, "data: [DONE]"), joined(lines));
				assertEquals("data: [DONE]", lines.get(lines.size() - 1));
			}
		}
	}

	@Test
	void truncatedUpstreamStreamEmitsAnEntryErrorAndNeverASuccessTerminal() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				var lines = new ArrayList<String>();
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"partial\"}", null, null))));
					assertFalse(transcoder.terminalSeen());
					lines.addAll(transcoder.onUpstreamComplete());
					assertTrue(transcoder.terminalSeen());
					// A terminal error is final: no resurrection, no second terminator.
					assertEquals(List.of(), transcoder.onUpstreamComplete());
					assertEquals(List.of(), transcoder.onUpstreamLine(data(chunk("{}", "stop", USAGE))));
				}
				assertEquals(0, count(lines, "[DONE]"), joined(lines));
				assertEquals(0, count(lines, "\"finish_reason\":\"stop\""), joined(lines));
				assertEquals(1, count(lines, "\"error\""), joined(lines));
				assertTrue(joined(lines).contains("upstream_error"), joined(lines));
				assertTrue(joined(lines).contains("upstream stream ended before terminal"), joined(lines));
			}
		}
	}

	@Test
	void anIllegalUpstreamChunkBecomesAnEntryErrorNotAnEscapingRuntimeException() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				var lines = new ArrayList<String>();
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					lines.addAll(transcoder.onUpstreamLine(data("{not json")));
					assertTrue(transcoder.terminalSeen());
					assertEquals(List.of(), transcoder.onUpstreamComplete());
				}
				assertEquals(1, count(lines, "\"error\""), joined(lines));
				assertEquals(0, count(lines, "[DONE]"), joined(lines));
				assertTrue(joined(lines).contains("upstream_error"), joined(lines));
			}
		}
	}

	@Test
	void anAbnormalUpstreamFinishReasonNeverBecomesASuccessTerminal() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				var lines = new ArrayList<String>();
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"partial\"}", null, null))));
					lines.addAll(transcoder.onUpstreamLine(data(chunk("{}", "aborted", null))));
					lines.addAll(transcoder.onUpstreamComplete());
				}
				assertEquals(0, count(lines, "[DONE]"), joined(lines));
				assertEquals(0, count(lines, "\"finish_reason\":\"stop\""), joined(lines));
				assertEquals(1, count(lines, "\"error\""), joined(lines));
				assertTrue(joined(lines).contains("aborted"), joined(lines));
			}
		}
	}

	@Test
	void afterHeadersErrorEmitsAnEntryErrorEventAndTerminates() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					transcoder.onUpstreamLine(data(chunk("{\"content\":\"partial\"}", null, null)));
					List<String> lines = transcoder
						.onUpstreamError(new IllegalStateException("connection reset by peer"));
					assertEquals(1, lines.size());
					assertTrue(lines.get(0).startsWith("data: "), lines.get(0));
					assertTrue(lines.get(0).contains("upstream_error"), lines.get(0));
					assertTrue(lines.get(0).contains("connection reset by peer"), lines.get(0));
					assertTrue(transcoder.terminalSeen());
					assertEquals(List.of(), transcoder.onUpstreamError(new IllegalStateException("again")));
					assertEquals(List.of(), transcoder.onUpstreamComplete());
				}
				// A throwable without a message still renders a stable error frame, never
				// a bare NPE.
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					List<String> lines = transcoder.onUpstreamError(new RuntimeException());
					assertEquals(1, lines.size());
					assertTrue(lines.get(0).contains("upstream_error"), lines.get(0));
					assertNotEquals("", lines.get(0));
				}
			}
		}
	}

	@Test
	void responsesAndMessagesStreamsKeepTheirOwnProtocolTerminatorWithoutDone() {
		try (var context = context()) {
			ConversionService service = service(context);
			record Case(ProtocolKind kind, String request, String terminal) {
			}
			var cases = List
				.of(new Case(ProtocolKind.RESPONSES,
						"{\"model\":\"" + CALLER_MODEL + "\",\"input\":\"hi\",\"stream\":true}",
						"event: response.completed"),
						new Case(ProtocolKind.MESSAGES,
								"{\"model\":\"" + CALLER_MODEL + "\",\"max_tokens\":1024,\"stream\":true,"
										+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
								"event: message_stop"));
			for (Case item : cases) {
				try (PreparedConversion prepared = service.prepare(parsed(item.kind(), item.request(), true),
						route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty())) {
					var lines = new ArrayList<String>();
					try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
						lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"hi\"}", null, null))));
						lines.addAll(transcoder.onUpstreamLine(data(chunk("{}", "stop", USAGE))));
						lines.addAll(transcoder.onUpstreamComplete());
						assertTrue(transcoder.terminalSeen(), item.kind().wireName());
						// Repeated end signals never duplicate this protocol's terminal
						// either.
						assertEquals(List.of(), transcoder.onUpstreamComplete(),
								item.kind().wireName() + ": repeated end must not duplicate the terminal");
					}
					assertEquals(1, count(lines, item.terminal()), item.kind().wireName() + ": " + joined(lines));
					assertEquals(0, count(lines, "[DONE]"), item.kind().wireName() + ": " + joined(lines));
				}
			}
		}
	}

	@Test
	void responsesAndMessagesTruncationEmitTheirOwnErrorEventWithoutASuccessTerminal() {
		try (var context = context()) {
			ConversionService service = service(context);
			record Case(ProtocolKind kind, String request, String terminal) {
			}
			var cases = List.of(new Case(ProtocolKind.RESPONSES,
					"{\"model\":\"" + CALLER_MODEL + "\",\"input\":\"hi\",\"stream\":true}", "response.completed"),
					new Case(ProtocolKind.MESSAGES,
							"{\"model\":\"" + CALLER_MODEL + "\",\"max_tokens\":1024,\"stream\":true,"
									+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
							"message_stop"));
			for (Case item : cases) {
				try (PreparedConversion prepared = service.prepare(parsed(item.kind(), item.request(), true),
						route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty())) {
					var lines = new ArrayList<String>();
					try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
						lines.addAll(transcoder.onUpstreamLine(data(chunk("{\"content\":\"partial\"}", null, null))));
						lines.addAll(transcoder.onUpstreamComplete());
					}
					String all = joined(lines);
					assertEquals(0, count(lines, item.terminal()), item.kind().wireName() + ": " + all);
					assertEquals(0, count(lines, "[DONE]"), item.kind().wireName() + ": " + all);
					assertTrue(all.contains("upstream_error"), item.kind().wireName() + ": " + all);
					assertTrue(all.contains("event: error"), item.kind().wireName() + ": " + all);
				}
			}
		}
	}

	// ---------- per-request state, bounded buffering and close ----------

	@Test
	void eachTranscoderOwnsItsStateAndCloseIsIdempotent() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				StreamTranscoder first = prepared.newStreamTranscoder();
				first.onUpstreamLine(data(chunk("{\"content\":\"hi\"}", null, null)));
				first.onUpstreamLine(data(chunk("{}", "stop", USAGE)));
				assertTrue(first.terminalSeen());

				StreamTranscoder second = prepared.newStreamTranscoder();
				assertNotSame(first, second);
				assertFalse(second.terminalSeen(), "a fresh transcoder must not inherit terminal state");
				List<String> fresh = second.onUpstreamLine(data(chunk("{\"content\":\"hi\"}", null, null)));
				assertEquals(1, fresh.size());
				assertTrue(fresh.get(0).contains("\"role\":\"assistant\""), fresh.get(0));

				// Closing one transcoder leaves the other usable, and close is
				// repeatable.
				first.close();
				first.close();
				assertEquals(List.of(), first.onUpstreamLine(data(chunk("{}", "stop", null))));
				assertEquals(List.of(), first.onUpstreamComplete());
				assertEquals(List.of(), first.onUpstreamError(new IllegalStateException("late")));
				assertFalse(second.terminalSeen());
				second.close();
				second.close();
			}
			// The prepared handle holds no native resource: close is a repeatable no-op.
			PreparedConversion again = service(context).prepare(parsed(ProtocolKind.CHAT, chatRequest(false), false),
					route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty());
			again.close();
			again.close();
		}
	}

	@Test
	void terminalSeenIsAMonotonicLatchThatSurvivesClose() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				// A successfully terminated stream: the latch must stay true after close
				// so a
				// transport that queries terminalSeen() in its finally block never
				// appends a
				// spurious error frame after [DONE].
				StreamTranscoder completed = prepared.newStreamTranscoder();
				completed.onUpstreamLine(data(chunk("{}", "stop", USAGE)));
				completed.onUpstreamComplete();
				assertTrue(completed.terminalSeen());
				completed.close();
				assertTrue(completed.terminalSeen(), "terminal latch must survive close");

				// An errored stream is terminal too: the latch must survive close
				// identically.
				StreamTranscoder errored = prepared.newStreamTranscoder();
				errored.onUpstreamLine(data(chunk("{\"content\":\"partial\"}", null, null)));
				errored.onUpstreamComplete();
				assertTrue(errored.terminalSeen());
				errored.close();
				assertTrue(errored.terminalSeen(), "error latch must survive close");

				// A stream closed before any terminal never claims one.
				StreamTranscoder fresh = prepared.newStreamTranscoder();
				fresh.close();
				assertFalse(fresh.terminalSeen(), "no terminal was emitted before close");
			}
		}
	}

	@Test
	void longStreamReturnsOnlyTheCurrentChunkPerCallWithoutAccumulating() {
		try (var context = context()) {
			try (PreparedConversion prepared = service(context).prepare(
					parsed(ProtocolKind.CHAT, chatRequest(true), true), route(UPSTREAM_MODEL), "tenant-1", "project-1",
					DegradationGrant.empty())) {
				try (StreamTranscoder transcoder = prepared.newStreamTranscoder()) {
					// Each content delta yields exactly its own frame; the transcoder
					// holds no
					// growing buffer, so call N never re-emits the lines of calls 1..N-1.
					for (int i = 0; i < 500; i++) {
						List<String> lines = transcoder
							.onUpstreamLine(data(chunk("{\"content\":\"t" + i + "\"}", null, null)));
						assertEquals(1, lines.size(), "chunk " + i + " must yield exactly one frame");
						assertTrue(lines.get(0).contains("\"content\":\"t" + i + "\""), lines.get(0));
						assertFalse(transcoder.terminalSeen());
					}
					List<String> end = transcoder.onUpstreamLine(data(chunk("{}", "stop", USAGE)));
					assertTrue(transcoder.terminalSeen());
					assertEquals(1, count(end, "\"finish_reason\":\"stop\""), joined(end));
				}
			}
		}
	}

	@Test
	void twoRuntimesDoNotShareConversionState() {
		try (var a = context(); var b = context()) {
			ConversionService first = service(a);
			ConversionService second = service(b);
			assertNotSame(first, second);
			try (PreparedConversion preparedA = first.prepare(parsed(ProtocolKind.CHAT, chatRequest(true), true),
					route(UPSTREAM_MODEL), "tenant-1", "project-1", DegradationGrant.empty());
					PreparedConversion preparedB = second.prepare(parsed(ProtocolKind.CHAT, chatRequest(true), true),
							route(UPSTREAM_MODEL), "tenant-2", "project-2", DegradationGrant.empty())) {
				StreamTranscoder transcoderA = preparedA.newStreamTranscoder();
				StreamTranscoder transcoderB = preparedB.newStreamTranscoder();
				transcoderA.onUpstreamLine(data(chunk("{\"content\":\"a\"}", null, null)));
				transcoderA.onUpstreamLine(data(chunk("{}", "stop", USAGE)));
				assertTrue(transcoderA.terminalSeen());
				assertFalse(transcoderB.terminalSeen());
				assertEquals(1, transcoderB.onUpstreamLine(data(chunk("{\"content\":\"b\"}", null, null))).size());
				transcoderA.close();
				transcoderB.close();
			}
		}
	}

}