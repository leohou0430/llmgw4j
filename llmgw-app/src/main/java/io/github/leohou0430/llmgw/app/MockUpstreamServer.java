package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.MultipartProbe;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Deterministic mock upstream speaking ALL FIVE gateway protocols (chat / responses /
 * messages / images / videos), with configurable delay and SSE chunk count. Used by smoke
 * scripts and ALL benchmark runs so results are reproducible.
 *
 * <p>
 * Each streaming protocol emits its OWN delta and terminator frames -- the gateway must
 * forward them verbatim and never fabricate a terminator:
 * <ul>
 * <li>chat: {@code data: {...delta...}} ... {@code data: [DONE]}</li>
 * <li>responses: {@code event: response.output_text.delta} ...
 * {@code event: response.completed} (usage inside response.usage)</li>
 * <li>messages: {@code event: content_block_delta} ... {@code data: message_delta}
 * (usage) ... {@code event: message_stop}</li>
 * </ul>
 *
 * <p>
 * Failure injection for streaming error-contract tests (all markers are inert for every
 * benchmark payload, so benchmark numbers are unchanged):
 * <ul>
 * <li>{@code mock-fail-after-N}: emit N chunks, then throw IOException so the connection
 * breaks abnormally. Tests the thrown-error path.</li>
 * <li>{@code mock-eof-after-N}: emit N chunks (N=0 means none), then close the chunked
 * response CLEANLY with no terminator and no exception. Tests silent truncation, which a
 * naive implementation mistakes for a successful stream.</li>
 * <li>{@code mock-status-N}: answer with HTTP status N and a JSON error body, for BOTH
 * stream and non-stream POSTs. Tests that a 4xx/5xx body is never forwarded as SSE data
 * lines and always becomes a gateway 502.</li>
 * <li>{@code mock-done-then-fail}: deliver the full stream including the protocol
 * terminator and THEN break the connection. A completed protocol exchange must stay a
 * success, with no extra error event.</li>
 * <li>{@code mock-echo-hash}: the non-stream answer echoes a hash of the COMPLETE request
 * body. Two requests with the same prompt but different sampling parameters must
 * therefore produce different answers -- a cache keyed only on model+prompt would wrongly
 * return the first one.</li>
 * </ul>
 *
 * <p>
 * Video tasks are asynchronous: create mints a task id, poll returns
 * queued/in_progress/completed based on elapsed time, and content is served (video/mp4)
 * only once completed. Unknown task ids 404 -- which is what makes a second gateway
 * instance (with its own mock) a real sticky-routing probe.
 */
@Component
public class MockUpstreamServer implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(MockUpstreamServer.class);

	private static final String FAIL_MARKER = "mock-fail-after-";

	private static final String EOF_MARKER = "mock-eof-after-";

	private static final String DEEPSEEK_MARKER = "mock-deepseek";

	private static final String DEEPSEEK_TEXT = "Hello world";

	private static final String DEEPSEEK_REASONING = "Let me think";

	private static final String DEEPSEEK_TOOL_ID = "call_mock_1";

	private static final String DEEPSEEK_TOOL_NAME = "get_weather";

	private static final String DEEPSEEK_TOOL_ARGUMENTS = "{\"city\":\"SF\"}";

	private static final int DEEPSEEK_PROMPT_TOKENS = 7;

	private static final int DEEPSEEK_CACHED_TOKENS = 3;

	private static final int DEEPSEEK_REASONING_TOKENS = 2;

	/**
	 * Unterminated chunk JSON: the transport still completes, so the error must be caught
	 * in-band.
	 */
	private static final String DEEPSEEK_CORRUPT_CHUNK = "{\"id\":\"chatcmpl-mock-deepseek\",\"object\":\"chat.completion.chunk\","
			+ "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"broken";

	private static final int MAX_IMAGES = 10;

	/** Task sits in "queued" for this long, then "in_progress", then completes. */
	private static final long QUEUED_MS = 400;

	private static final long READY_MS = 1_200;

	private final GatewayProperties properties;

	private final Map<String, VideoTask> videoTasks = new ConcurrentHashMap<>();

	private final AtomicLong videoIds = new AtomicLong();

	/** v13: fires-exactly-once state for the mock-fail-once-status-N marker. */
	private final Map<String, java.util.concurrent.atomic.AtomicBoolean> onceMarkers = new ConcurrentHashMap<>();

	/** W4.3: batch ids minted by THIS mock (polling an unknown id 404s). */
	private final java.util.Set<String> knownBatchIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private HttpServer server;

	public MockUpstreamServer(GatewayProperties properties) {
		this.properties = properties;
		if (!properties.getMock().isEnabled()) {
			return;
		}
		try {
			server = HttpServer.create(new InetSocketAddress(properties.getMock().getPort()), 0);
			server.createContext("/mock/v1/chat/completions", this::handle);
			server.createContext("/mock/v1/responses", this::handle);
			server.createContext("/mock/v1/messages", this::handle);
			server.createContext("/mock/v1/images/generations", this::handle);
			server.createContext("/mock/v1/videos", this::handle);
			server.createContext("/mock/v1/guardrail", this::handleGuardrail);
			server.createContext("/mock/v1/embeddings", this::handleEmbeddings);
			server.createContext("/mock/v1/audio/transcriptions", this::handle);
			server.createContext("/mock/v1/files", this::handle);
			server.createContext("/mock/v1/batches", this::handle);
			server.createContext("/mock/v1/models", exchange -> writeJson(exchange, 200,
					"{\"object\":\"list\",\"data\":[" + "{\"id\":\"qwen-plus\"},{\"id\":\"mock-model\"}]}"));
			server.createContext("/mock/v1/rerank", exchange -> writeJson(exchange, 200,
					"{\"object\":\"list\",\"results\":[" + "{\"index\":0,\"relevance_score\":0.9}]}"));
			server.createContext("/mock/v1/audio/speech", exchange -> {
				// Binary pass-through probe: deterministic pseudo-mp4 bytes.
				byte[] audio = "mock-speech-bytes-".repeat(24).getBytes(StandardCharsets.UTF_8);
				decorate(exchange);
				exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
				exchange.sendResponseHeaders(200, audio.length);
				try (var out = exchange.getResponseBody()) {
					out.write(audio);
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			log.info("Mock upstream listening on port {}", server.getAddress().getPort());
		}
		catch (IOException e) {
			if (server != null) {
				server.stop(0);
			}
			throw new IllegalStateException("failed to start mock upstream", e);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if (path.startsWith("/mock/v1/videos/")) {
			handleVideoFetch(exchange, path);
			return;
		}
		// W4.3 completion: transcriptions / files / batches.
		if (path.equals("/mock/v1/audio/transcriptions")) {
			handleTranscriptions(exchange);
			return;
		}
		if (path.equals("/mock/v1/files") || path.startsWith("/mock/v1/files/")) {
			handleFiles(exchange, path);
			return;
		}
		if (path.equals("/mock/v1/batches") || path.startsWith("/mock/v1/batches/")) {
			handleBatches(exchange, path);
			return;
		}
		if (path.endsWith("/videos")) {
			handleVideoCreate(exchange);
			return;
		}
		if (path.endsWith("/responses")) {
			handleResponses(exchange);
			return;
		}
		if (path.endsWith("/messages")) {
			handleMessages(exchange);
			return;
		}
		if (path.endsWith("/images/generations")) {
			handleImages(exchange);
			return;
		}
		handleChat(exchange);
	}

	/**
	 * W4.1 external moderation engine emulation: {"action":"DENY"} when the prompt
	 * contains "deny-me", ALLOW otherwise. Same JSON contract HttpGuardrail speaks.
	 */
	private void handleGuardrail(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (body.contains("deny-me")) {
			writeJson(exchange, 200, "{\"action\":\"DENY\",\"reason\":\"mock engine flagged the prompt\"}");
		}
		else {
			writeJson(exchange, 200, "{\"action\":\"ALLOW\"}");
		}
	}

	/**
	 * W4.2 deterministic bag-of-words embedder: word hash -> dimension bucket,
	 * L2-normalized. Same word multiset (any order, any surrounding text) gives the SAME
	 * vector, which is what makes the semantic-cache smoke deterministic. 16 dimensions.
	 */
	private void handleEmbeddings(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String input = "";
		try {
			var root = tryParse(body);
			var inputNode = root.get("input");
			if (inputNode != null && inputNode.isTextual()) {
				input = inputNode.asText();
			}
		}
		catch (RuntimeException ignored) {
			// fall through with empty input
		}
		double[] vector = new double[16];
		for (String word : input.toLowerCase().split("[^a-z0-9]+")) {
			if (word.isEmpty()) {
				continue;
			}
			vector[Math.floorMod(word.hashCode(), vector.length)] += 1;
		}
		double norm = 0;
		for (double v : vector) {
			norm += v * v;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < vector.length; i++) {
				vector[i] /= norm;
			}
		}
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append(String.format(java.util.Locale.ROOT, "%.6f", vector[i]));
		}
		json.append(']');
		writeJson(exchange, 200, "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\","
				+ "\"index\":0,\"embedding\":" + json + "}]," + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}");
	}

	/** W4.3 transcriptions: JSON answer for a multipart upload. */
	private void handleTranscriptions(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		String model = MultipartProbe.field(body, "model");
		writeJson(exchange, 200, "{\"text\":\"mock transcript of " + body.length + " bytes\",\"model\":\"" + model
				+ "\",\"usage\":{\"type\":\"tokens\"," + "\"input_tokens\":1,\"output_tokens\":1}}");
	}

	/** W4.3 files: multipart upload / list / retrieve / delete. */
	private void handleFiles(HttpExchange exchange, String path) throws IOException {
		String method = exchange.getRequestMethod();
		if ("POST".equals(method)) {
			byte[] body = exchange.getRequestBody().readAllBytes();
			String purpose = MultipartProbe.field(body, "purpose");
			writeJson(exchange, 200, "{\"id\":\"file-" + videoIds.incrementAndGet()
					+ "\",\"object\":\"file\",\"bytes\":" + body.length + ",\"purpose\":\"" + purpose + "\"}");
			return;
		}
		if (path.equals("/mock/v1/files")) {
			writeJson(exchange, 200, "{\"object\":\"list\",\"data\":[]}");
			return;
		}
		String id = path.substring("/mock/v1/files/".length());
		if ("DELETE".equals(method)) {
			writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"deleted\":true}");
		}
		else if (path.endsWith("/content")) {
			byte[] content = ("mock-file-content-" + id + "-").repeat(16).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, content.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(content);
			}
		}
		else {
			writeJson(exchange, 200,
					"{\"id\":\"" + id + "\",\"object\":\"file\"," + "\"bytes\":128,\"purpose\":\"fine-tune\"}");
		}
	}

	/**
	 * W4.3 batches: async job like videos — create mints a sticky batch id; polling an
	 * UNKNOWN id 404s, exactly like video tasks.
	 */
	private void handleBatches(HttpExchange exchange, String path) throws IOException {
		if (path.equals("/mock/v1/batches")) {
			String id = "batch-" + videoIds.incrementAndGet();
			knownBatchIds.add(id);
			writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"object\":\"batch\",\"status\":\"validating\"}");
			return;
		}
		String id = path.substring("/mock/v1/batches/".length());
		if (path.endsWith("/cancel")) {
			String batchId = id.substring(0, id.length() - "/cancel".length());
			if (!knownBatchIds.contains(batchId)) {
				writeJson(exchange, 404, "{\"error\":{\"message\":\"unknown batch " + batchId + "\"}}");
				return;
			}
			writeJson(exchange, 200, "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"cancelled\"}");
			return;
		}
		if (!knownBatchIds.contains(id)) {
			writeJson(exchange, 404, "{\"error\":{\"message\":\"unknown batch " + id + "\"}}");
			return;
		}
		long elapsed = System.currentTimeMillis() % 2_000;
		writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"object\":\"batch\",\"status\":\""
				+ (elapsed < 1_000 ? "in_progress" : "completed") + "\"}");
	}

	// ---------------------------------------------------------------- chat

	private void handleChat(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (denyOnceWithStatus(exchange, body) || denyWithStatus(exchange, body)) {
			return;
		}
		boolean stream = body.contains("\"stream\"") && body.contains("true");
		if (body.contains(DEEPSEEK_MARKER)) {
			handleDeepSeekChat(exchange, body, stream);
			return;
		}
		if (stream) {
			int chunks = Math.max(1, properties.getMock().getChunks());
			IntFunction<String> delta = i -> {
				String json = "{\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
						+ "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + "\"}}]}";
				return "data: " + json + "\n\n";
			};
			String usage = "data: {\"usage\":{\"completion_tokens\":" + chunks + "}}\n\n";
			writeStream(exchange, body, delta, usage, "data: [DONE]\n\n");
			return;
		}
		String content = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
		// v13 ingress probes inside the response BODY (header echoes would be
		// filtered by the gateway's egress allowlist -- by design).
		var requestHeaders = exchange.getRequestHeaders();
		String echoVersion = requestHeaders.getFirst("anthropic-version");
		String echoAuth = requestHeaders.getFirst("Authorization");
		String apiKey = requestHeaders.getFirst("x-api-key");
		String authStyle = apiKey != null ? "x-api-key" : echoAuth != null ? "bearer" : "none";
		String json = "{\"id\":\"mock\",\"object\":\"chat.completion\","
				+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}],"
				+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}" + ",\"echoAuthStyle\":\"" + authStyle + "\""
				+ (echoVersion != null ? ",\"echoAnthropicVersion\":\"" + echoVersion + "\"" : "")
				+ (echoAuth != null ? ",\"echoAuthorization\":\"" + echoAuth + "\"" : "")
				+ (apiKey != null ? ",\"echoApiKeyPresent\":true" : "") + "}";
		writeJson(exchange, 200, json);
	}

	// ------------------------------------------------- deepseek chat mode

	/**
	 * T1.13a: marker-gated DeepSeek Chat wire format for the three-protocol acceptance
	 * runs. Every payload without a {@code mock-deepseek*} marker keeps the legacy frames
	 * byte for byte, so smoke/benchmark behaviour is unchanged.
	 */
	private void handleDeepSeekChat(HttpExchange exchange, String body, boolean stream) throws IOException {
		boolean tools = body.contains(DEEPSEEK_MARKER + "-tools");
		boolean thinking = body.contains(DEEPSEEK_MARKER + "-thinking");
		int chunks = Math.max(1, properties.getMock().getChunks());
		if (stream) {
			int corruptAt = corruptChunkAt(body);
			IntFunction<String> delta = i -> "data: "
					+ (i == corruptAt ? DEEPSEEK_CORRUPT_CHUNK : deepSeekDeltaFrame(i, chunks, thinking, tools))
					+ "\n\n";
			// usage rides the FINAL chunk: once finish_reason is seen the decoder ignores
			// later
			// chunks, so a trailing usage-only frame would silently lose the billing
			// numbers.
			writeStream(exchange, body, delta, null, "data: [DONE]\n\n");
			return;
		}
		writeJson(exchange, 200, deepSeekCompletion(thinking, tools));
	}

	private static String deepSeekDeltaFrame(int i, int chunks, boolean thinking, boolean tools) {
		boolean last = i == chunks - 1;
		String delta = tools ? deepSeekToolDelta(i, chunks, last)
				: thinking ? deepSeekThinkingDelta(i, chunks, last) : deepSeekTextDelta(i, chunks, last);
		return deepSeekChunk(delta, last ? (tools ? "tool_calls" : "stop") : null,
				last ? deepSeekUsage(chunks, thinking) : null);
	}

	private static String deepSeekTextDelta(int i, int chunks, boolean last) {
		String content = chunks == 1 ? DEEPSEEK_TEXT : last ? null : splitEvenly(DEEPSEEK_TEXT, chunks - 1).get(i);
		return content == null ? "{}" : "{\"content\":" + jsonString(content) + "}";
	}

	private static String deepSeekThinkingDelta(int i, int chunks, boolean last) {
		int contentFrames = chunks - 1;
		// chunks==1 的唯一帧既是 last 又必须携带完整答案，故仅 contentFrames>0 时 last 才空帧。
		if (last && contentFrames > 0) {
			return "{}";
		}
		if (contentFrames <= 1) {
			return "{\"reasoning_content\":" + jsonString(DEEPSEEK_REASONING) + ",\"content\":"
					+ jsonString(DEEPSEEK_TEXT) + "}";
		}
		int reasoningFrames = Math.max(1, contentFrames / 2);
		if (i < reasoningFrames) {
			return "{\"reasoning_content\":" + jsonString(splitEvenly(DEEPSEEK_REASONING, reasoningFrames).get(i))
					+ "}";
		}
		return "{\"content\":"
				+ jsonString(splitEvenly(DEEPSEEK_TEXT, contentFrames - reasoningFrames).get(i - reasoningFrames))
				+ "}";
	}

	/**
	 * Opening fragment carries id/type/name; later fragments carry index + arguments
	 * only.
	 */
	private static String deepSeekToolDelta(int i, int chunks, boolean last) {
		int argumentFrames = chunks - 2;
		if (last) {
			return chunks == 1 ? "{\"tool_calls\":[" + deepSeekToolCall(true, DEEPSEEK_TOOL_ARGUMENTS) + "]}" : "{}";
		}
		if (i == 0) {
			return "{\"tool_calls\":[" + deepSeekToolCall(true, argumentFrames <= 0 ? DEEPSEEK_TOOL_ARGUMENTS : "")
					+ "]}";
		}
		return "{\"tool_calls\":["
				+ deepSeekToolCall(false, splitEvenly(DEEPSEEK_TOOL_ARGUMENTS, argumentFrames).get(i - 1)) + "]}";
	}

	private static String deepSeekToolCall(boolean opening, String arguments) {
		return "{\"index\":0" + (opening ? ",\"id\":\"" + DEEPSEEK_TOOL_ID + "\",\"type\":\"function\"" : "")
				+ ",\"function\":{" + (opening ? "\"name\":\"" + DEEPSEEK_TOOL_NAME + "\"," : "") + "\"arguments\":"
				+ jsonString(arguments) + "}}";
	}

	private static String deepSeekCompletion(boolean thinking, boolean tools) {
		StringBuilder message = new StringBuilder("{\"role\":\"assistant\"");
		if (thinking) {
			message.append(",\"reasoning_content\":").append(jsonString(DEEPSEEK_REASONING));
		}
		if (tools) {
			message.append(",\"content\":null,\"tool_calls\":[{\"id\":\"")
				.append(DEEPSEEK_TOOL_ID)
				.append("\",\"type\":\"function\",\"function\":{\"name\":\"")
				.append(DEEPSEEK_TOOL_NAME)
				.append("\",\"arguments\":")
				.append(jsonString(DEEPSEEK_TOOL_ARGUMENTS))
				.append("}}]");
		}
		else {
			message.append(",\"content\":").append(jsonString(DEEPSEEK_TEXT));
		}
		message.append('}');
		return deepSeekCompletion(message.toString(), tools ? "tool_calls" : "stop", thinking);
	}

	private static String deepSeekChunk(String deltaJson, String finishReason, String usageJson) {
		return "{\"id\":\"chatcmpl-mock-deepseek\",\"object\":\"chat.completion.chunk\","
				+ "\"created\":1730000000,\"model\":\"deepseek-flash\"," + "\"choices\":[{\"index\":0,\"delta\":"
				+ deltaJson + ",\"finish_reason\":" + (finishReason == null ? "null" : jsonString(finishReason)) + "}]"
				+ (usageJson == null ? "" : ",\"usage\":" + usageJson) + "}";
	}

	private static String deepSeekCompletion(String messageJson, String finishReason, boolean thinking) {
		return "{\"id\":\"chatcmpl-mock-deepseek\",\"object\":\"chat.completion\","
				+ "\"created\":1730000000,\"model\":\"deepseek-flash\"," + "\"choices\":[{\"index\":0,\"message\":"
				+ messageJson + ",\"finish_reason\":" + jsonString(finishReason) + "}]," + "\"usage\":"
				+ deepSeekUsage(1, thinking) + "}";
	}

	private static String deepSeekUsage(int completionTokens, boolean thinking) {
		return "{\"prompt_tokens\":" + DEEPSEEK_PROMPT_TOKENS + ",\"completion_tokens\":" + completionTokens
				+ ",\"total_tokens\":" + (DEEPSEEK_PROMPT_TOKENS + completionTokens)
				+ ",\"prompt_tokens_details\":{\"cached_tokens\":" + DEEPSEEK_CACHED_TOKENS + "}"
				+ (thinking ? ",\"completion_tokens_details\":{\"reasoning_tokens\":" + DEEPSEEK_REASONING_TOKENS + "}"
						: "")
				+ "}";
	}

	/** Even split so any configured chunk count reassembles to exactly the same text. */
	private static List<String> splitEvenly(String value, int parts) {
		List<String> pieces = new ArrayList<>(parts);
		int previous = 0;
		for (int i = 1; i <= parts; i++) {
			int end = (int) Math.round((double) value.length() * i / parts);
			pieces.add(value.substring(previous, end));
			previous = end;
		}
		return pieces;
	}

	private static String jsonString(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	// ------------------------------------------------------------ responses

	private void handleResponses(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (denyOnceWithStatus(exchange, body) || denyWithStatus(exchange, body)) {
			return;
		}
		boolean stream = body.contains("\"stream\"") && body.contains("true");
		if (stream) {
			int chunks = Math.max(1, properties.getMock().getChunks());
			IntFunction<String> delta = i -> {
				String json = "{\"type\":\"response.output_text.delta\"," + "\"delta\":\"tok" + i + "\"}";
				return "event: response.output_text.delta\ndata: " + json + "\n\n";
			};
			String completed = "event: response.completed\ndata: {\"type\":\"response.completed\","
					+ "\"response\":{\"id\":\"resp_mock\",\"usage\":{\"input_tokens\":1," + "\"output_tokens\":"
					+ chunks + "}}}\n\n";
			writeStream(exchange, body, delta, null, completed);
			return;
		}
		String text = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
		String json = "{\"id\":\"resp_mock\",\"object\":\"response\",\"status\":\"completed\","
				+ "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\"," + "\"text\":\"" + text
				+ "\"}]}]," + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
		writeJson(exchange, 200, json);
	}

	// ------------------------------------------------------------- messages

	private void handleMessages(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (denyOnceWithStatus(exchange, body) || denyWithStatus(exchange, body)) {
			return;
		}
		boolean stream = body.contains("\"stream\"") && body.contains("true");
		if (stream) {
			int chunks = Math.max(1, properties.getMock().getChunks());
			IntFunction<String> delta = i -> {
				String json = "{\"type\":\"content_block_delta\",\"index\":0,"
						+ "\"delta\":{\"type\":\"text_delta\",\"text\":\"tok" + i + "\"}}";
				return "event: content_block_delta\ndata: " + json + "\n\n";
			};
			String usage = "data: {\"type\":\"message_delta\"," + "\"delta\":{\"stop_reason\":\"end_turn\"},"
					+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":" + chunks + "}}\n\n";
			String stop = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
			writeStream(exchange, body, delta, usage, stop);
			return;
		}
		String text = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
		String json = "{\"id\":\"msg_mock\",\"type\":\"message\",\"role\":\"assistant\","
				+ "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
				+ "\"model\":\"mock\",\"stop_reason\":\"end_turn\","
				+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
		writeJson(exchange, 200, json);
	}

	// --------------------------------------------------------------- images

	private void handleImages(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (denyOnceWithStatus(exchange, body) || denyWithStatus(exchange, body)) {
			return;
		}
		JsonNode root = tryParse(body);
		int n = Math.min(MAX_IMAGES, Math.max(1, root.path("n").asInt(1)));
		StringBuilder data = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				data.append(',');
			}
			String b64 = Base64.getEncoder()
				.encodeToString(("mock-image-" + i + "-" + hashPrefix(body)).getBytes(StandardCharsets.UTF_8));
			data.append("{\"b64_json\":\"").append(b64).append("\"}");
		}
		String json = "{\"created\":1730000000,\"data\":[" + data + "]}";
		writeJson(exchange, 200, json);
	}

	// --------------------------------------------------------------- videos

	private void handleVideoCreate(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (denyOnceWithStatus(exchange, body) || denyWithStatus(exchange, body)) {
			return;
		}
		JsonNode root = tryParse(body);
		String id = "video-" + videoIds.incrementAndGet();
		String model = root.path("model").asText("mock");
		videoTasks.put(id, new VideoTask(model));
		writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\"queued\"," + "\"model\":\"" + model + "\"}");
	}

	private void handleVideoFetch(HttpExchange exchange, String path) throws IOException {
		String id = path.substring("/mock/v1/videos/".length());
		boolean content = id.endsWith("/content");
		if (content) {
			id = id.substring(0, id.length() - "/content".length());
		}
		VideoTask task = videoTasks.get(id);
		if (task == null) {
			// Unknown upstream: exactly what a second gateway instance sees
			// when polling another instance's task -- the sticky proof.
			writeJson(exchange, 404, "{\"error\":{\"message\":\"unknown video task\"}}");
			return;
		}
		if (content) {
			if (!"completed".equals(task.status(System.currentTimeMillis()))) {
				writeJson(exchange, 409, "{\"error\":{\"message\":\"video not ready\"}}");
				return;
			}
			byte[] bytes = ("mock-video-content-" + id + "-").repeat(16).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "video/mp4");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			return;
		}
		writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\"" + task.status(System.currentTimeMillis())
				+ "\",\"model\":\"" + task.model() + "\",\"progress\":50}");
	}

	// ----------------------------------------------------- shared plumbing

	/**
	 * The streaming skeleton is protocol-independent: N delta frames with failure
	 * injection, then a usage frame, then the protocol terminator.
	 */
	private void writeStream(HttpExchange exchange, String body, IntFunction<String> deltaFrame, String usageFrame,
			String terminatorFrame) throws IOException {
		int failAfter = failAfter(body);
		int eofAfter = eofAfter(body);
		boolean doneThenFail = body.contains("mock-done-then-fail");
		decorate(exchange);
		// v13 first-byte watchdog bait: headers deliberately late.
		int stallHeaders = markerCount(body, "mock-stall-headers-", 0);
		if (stallHeaders > 0) {
			sleep(stallHeaders);
		}
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		// Deliberately NOT try-with-resources: the injector must be able to
		// abort a chunked response without writing its terminating chunk.
		OutputStream out = exchange.getResponseBody();
		sleep(properties.getMock().getDelayMs());
		int chunks = Math.max(1, properties.getMock().getChunks());
		// v13 per-request chunk interval override (heartbeat test bait).
		int intervalOverride = markerCount(body, "mock-chunk-interval-", -1);
		long chunkInterval = intervalOverride >= 0 ? intervalOverride : properties.getMock().getChunkIntervalMs();
		// v13 idle-read watchdog bait: silence after the FIRST chunk.
		int stallMid = markerCount(body, "mock-stall-mid-", 0);
		// v13 SSE ingress probe: first frame reports which probe headers the
		// mock actually received (in-band, immune to the egress allowlist).
		String headerProbe = null;
		if (body.contains("mock-echo-headers")) {
			var requestHeaders = exchange.getRequestHeaders();
			StringBuilder probe = new StringBuilder("hdrs{");
			String av = requestHeaders.getFirst("anthropic-version");
			if (av != null) {
				probe.append("anthropic-version=").append(av).append('|');
			}
			String au = requestHeaders.getFirst("Authorization");
			if (au != null) {
				probe.append("Authorization=").append(au).append('|');
			}
			String ak = requestHeaders.getFirst("x-api-key");
			if (ak != null) {
				probe.append("x-api-key=").append(ak).append('|');
			}
			String xp = requestHeaders.getFirst("x-private");
			if (xp != null) {
				probe.append("x-private=").append(xp).append('|');
			}
			probe.append('}');
			headerProbe = probe.toString();
		}
		if (eofAfter == 0) {
			// Clean EOF with zero frames: the hardest truncation to notice.
			out.close();
			return;
		}
		for (int i = 0; i < chunks; i++) {
			String frame = deltaFrame.apply(i);
			if (headerProbe != null && i == 0) {
				frame = "data: {\"debug-headers\":\"" + headerProbe + "\"}\n\n";
			}
			out.write(frame.getBytes(StandardCharsets.UTF_8));
			out.flush();
			if (failAfter > 0 && i + 1 == failAfter) {
				throw new IOException("simulated upstream crash after " + failAfter + " chunks");
			}
			if (eofAfter > 0 && i + 1 == eofAfter) {
				// Graceful close: writes the terminating HTTP chunk, so the
				// client sees a perfectly normal EOF and NO exception.
				out.close();
				return;
			}
			if (stallMid > 0 && i == 0) {
				sleep(stallMid);
			}
			sleep(chunkInterval);
		}
		if (usageFrame != null) {
			out.write(usageFrame.getBytes(StandardCharsets.UTF_8));
		}
		out.write(terminatorFrame.getBytes(StandardCharsets.UTF_8));
		out.flush();
		if (doneThenFail) {
			// Terminator already on the wire. Abnormally drop the connection
			// afterwards without writing the terminating HTTP chunk.
			throw new IOException("simulated disconnect after protocol terminator");
		}
		out.close();
	}

	/** Answers with the injected status; true = handled, false = no marker. */
	private boolean denyWithStatus(HttpExchange exchange, String body) throws IOException {
		int forcedStatus = markerCount(body, "mock-status-", 0);
		if (forcedStatus <= 0) {
			return false;
		}
		if (body.contains("mock-retry-after")) {
			// B9: upstream-declared Retry-After (seconds) — the gateway's retry
			// planner must wait at least this long before re-attempting.
			exchange.getResponseHeaders().add("Retry-After", "1");
		}
		String detail = "injected upstream status " + forcedStatus;
		if (body.contains("mock-context-overflow")) {
			// B10: context-overflow recognition probe.
			detail = "This model's maximum context length is 8192 tokens, "
					+ "however your messages resulted in 12000 tokens";
		}
		writeJson(exchange, forcedStatus, "{\"error\":{\"message\":\"" + detail + "\"}}");
		return true;
	}

	/**
	 * v13 retry-contract bait: the FIRST request carrying {@code mock-fail-once-status-N}
	 * fails with status N, every later one proceeds. A gateway that retries the same
	 * route recovers (200); a gateway that retries a 400 is caught by the 400-variant
	 * (502).
	 */
	private boolean denyOnceWithStatus(HttpExchange exchange, String body) throws IOException {
		int forcedStatus = markerCount(body, "mock-fail-once-status-", 0);
		if (forcedStatus <= 0) {
			return false;
		}
		java.util.concurrent.atomic.AtomicBoolean fired = onceMarkers.computeIfAbsent(
				"mock-fail-once-status-" + forcedStatus, key -> new java.util.concurrent.atomic.AtomicBoolean());
		if (fired.compareAndSet(false, true)) {
			if (body.contains("mock-retry-after")) {
				// B9: same Retry-After contract as the always-fail variant.
				exchange.getResponseHeaders().add("Retry-After", "1");
			}
			writeJson(exchange, forcedStatus,
					"{\"error\":{\"message\":\"injected once status " + forcedStatus + "\"}}");
			return true;
		}
		return false;
	}

	/**
	 * v13 header-passthrough probes, applied to every mock response: x-echo-* mirrors
	 * what the gateway actually forwarded upstream, x-ratelimit-remaining exercises the
	 * egress allowlist, and x-private-upstream must never reach the client.
	 */
	private void decorate(HttpExchange exchange) {
		var requestHeaders = exchange.getRequestHeaders();
		String anthropicVersion = requestHeaders.getFirst("anthropic-version");
		if (anthropicVersion != null) {
			exchange.getResponseHeaders().add("x-echo-anthropic-version", anthropicVersion);
		}
		String auth = requestHeaders.getFirst("Authorization");
		if (auth != null) {
			exchange.getResponseHeaders().add("x-echo-authorization", auth);
		}
		String priv = requestHeaders.getFirst("x-private");
		if (priv != null) {
			exchange.getResponseHeaders().add("x-echo-x-private", priv);
		}
		exchange.getResponseHeaders().add("x-ratelimit-remaining", "424242");
		exchange.getResponseHeaders().add("x-private-upstream", "secret-marker");
		// Ground-truth probe, gated: joining every received header name is
		// benchmark-visible work, so only do it when the load explicitly asks.
		if (requestHeaders.getFirst("x-llmgw-probe") != null) {
			exchange.getResponseHeaders().add("x-echo-received", String.join(",", requestHeaders.keySet()));
		}
	}

	private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
		decorate(exchange);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/** Malformed bodies fall back to an empty object, never a 500. */
	private static JsonNode tryParse(String body) {
		try {
			return ProtocolCodec.tryParse(body);
		}
		catch (RuntimeException e) {
			return ProtocolCodec.tryParse("{}");
		}
	}

	/** 0 = no marker; the two markers are mutually exclusive in practice. */
	private static int failAfter(String body) {
		return markerCount(body, FAIL_MARKER, 0);
	}

	/** -1 = no marker, 0 = close immediately, N = close after N chunks. */
	private static int eofAfter(String body) {
		return markerCount(body, EOF_MARKER, -1);
	}

	/**
	 * T1.13a corrupt-frame injection, deepseek mode only: {@code mock-corrupt-chunk} hits
	 * frame 0, {@code mock-corrupt-chunk-N} frame N. -1 = inert, so legacy payloads stay
	 * byte-identical.
	 */
	private static int corruptChunkAt(String body) {
		int at = markerCount(body, "mock-corrupt-chunk-", -1);
		if (at >= 0) {
			// N>=chunks 时 writeStream 的 i 循环永不命中故惰性；非数字/溢出后缀经 markerCount 回退到裸 marker→帧
			// 0。
			return at;
		}
		return body.contains("mock-corrupt-chunk") ? 0 : -1;
	}

	private static int markerCount(String body, String marker, int absent) {
		int index = body.indexOf(marker);
		if (index < 0) {
			return absent;
		}
		int start = index + marker.length();
		int end = start;
		while (end < body.length() && Character.isDigit(body.charAt(end))) {
			end++;
		}
		if (end == start) {
			return absent;
		}
		try {
			return Integer.parseInt(body.substring(start, end));
		}
		catch (NumberFormatException e) {
			return absent;
		}
	}

	private static String hashPrefix(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash).substring(0, 12);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void sleep(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class VideoTask {

		private final String model;

		private final long createdAt = System.currentTimeMillis();

		private VideoTask(String model) {
			this.model = model;
		}

		private String status(long now) {
			long age = now - createdAt;
			if (age < QUEUED_MS) {
				return "queued";
			}
			return age < READY_MS ? "in_progress" : "completed";
		}

		private String model() {
			return model;
		}

	}

	/**
	 * The actual loopback port the mock bound (ephemeral when configured with port 0).
	 */
	public int port() {
		return server != null ? server.getAddress().getPort() : properties.getMock().getPort();
	}

	@Override
	public void destroy() {
		if (server != null) {
			server.stop(0);
		}
	}

}
