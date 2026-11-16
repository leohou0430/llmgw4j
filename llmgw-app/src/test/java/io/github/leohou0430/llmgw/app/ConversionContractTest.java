package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.AppliedDegradation;
import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.Violation;
import io.github.leohou0430.llmgw.conversion.chat.ChatEntryCodec;
import io.github.leohou0430.llmgw.conversion.messages.MessagesEntryCodec;
import io.github.leohou0430.llmgw.conversion.responses.ResponsesEntryCodec;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.InMemoryTaskRouteSticky;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.engine.vt.VirtualThreadGatewayController;
import io.github.leohou0430.llmgw.engine.webflux.WebfluxGatewayController;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.github.leohou0430.llmgw.provider.http.jdk.JdkHttpUpstream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.14a: the three-entry x six-scenario controlled contract, driven through the REAL entry codecs,
 * the REAL DeepSeek transcoder and BOTH real data planes against the REAL {@link MockUpstreamServer}
 * over loopback HTTP (no network beyond 127.0.0.1, no real provider).
 *
 * <p>The truth is the versioned fixture under {@code tools/acceptance/stage1/fixtures}: every asserted
 * literal is read from {@code step.expected}, never recomputed from gateway code, so a codec regression
 * cannot make the test agree with itself. Coverage so far: chat, responses and messages s1-s6 on both
 * engines; the pin helper is deliberately strict -- an unimplemented pin key FAILS instead of being
 * silently skipped, so later slices must extend it explicitly.
 */
@Timeout(60)
class ConversionContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path FIXTURES = fixturesDir();
    private static final JsonNode MANIFEST = readJson(FIXTURES.resolve("manifest.json"));

    /** Both real data planes must agree on the identical controlled upstream state (parity). */
    private enum Engine { WEBFLUX, VT }

    private static String engineLabel(Engine engine) {
        return switch (engine) {
            case WEBFLUX -> "webflux";
            case VT -> "vt";
        };
    }

    /**
     * The drive's process-scoped continuation key and project id. {@code PermitChain.enter} allows with
     * keyId "fixture-tenant" and stage 1 maps tenantId == projectId == keyId, so a cross-project negative
     * must be minted with THIS key (HMAC passes) and a DIFFERENT project (project binding fails).
     */
    private static final String DRIVE_CONTINUATION_KEY = "contract-test-continuation-key";
    private static final String DRIVE_PROJECT_ID = "fixture-tenant";

    private static final Set<String> ERROR_PIN_KEYS = Set.of(
            "status", "code", "param", "reason", "error_type", "finished", "active_permits", "upstream_requests");
    private static final Set<String> DEGRADATION_KEYS =
            Set.of("id", "field_path", "header", "outbound_field", "outbound_equals");
    private static final Set<String> RESOURCE_DENIAL_KEYS =
            Set.of("status", "message", "error_type", "finished", "active_permits", "upstream_requests");
    private static final Set<String> OPENAI_ERROR_KEYS = Set.of("message", "type", "code", "param");
    private static final Set<String> MESSAGES_ERROR_KEYS = Set.of("type", "message");

    /**
     * {@code upstreamBody}/{@code upstreamPassHeaders} are NULL when the real upstream was never called:
     * a pre-header capability rejection must not touch the transport at all, so "no outbound request" is
     * itself an observable pin rather than an exception from the recorder.
     */
    private record Result(int status, String body, List<Boolean> finished, int activePermits,
                          String upstreamBody, int upstreamCancels, Map<String, String> upstreamPassHeaders,
                          int upstreamRequests) {
    }

    /**
     * A CANCELLED drive: the body is only what reached the client before the disconnect, so the
     * success-shaped {@link Result} pins must NOT be reused. The two transport-release signals are
     * engine-specific observations of the same contract -- the reactive plane cancels the real
     * upstream {@link Flow.Subscription} (which aborts the HTTP exchange), the servlet plane's
     * blocking line loop exits and JdkHttpUpstream's try-with-resources closes the response body.
     */
    private record CancelResult(int status, String body, List<Boolean> finished, int activePermits,
                                String upstreamBody, boolean firstFrameReceived, int upstreamCancels,
                                boolean blockingExited, int upstreamRequests) {
    }

    private MockUpstreamServer mock;
    private int port;

    @AfterEach
    void stop() {
        if (mock != null) {
            mock.destroy();
            mock = null;
        }
    }

    // ---------------------------------------------------------------- fixture loading

    /** Resolve from the module working dir (surefire) or the repo root (IDE / aggregate run). */
    private static Path fixturesDir() {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fromModule = base.resolve("../tools/acceptance/stage1/fixtures").normalize();
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return base.resolve("tools/acceptance/stage1/fixtures").normalize();
    }

    private static JsonNode readJson(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("missing fixture file: " + path, e);
        }
    }

    /** The manifest index is the authority for entry/scenario -> file; no hard-coded file names. */
    private static JsonNode fixture(String entry, int scenario) {
        for (JsonNode row : MANIFEST.path("index")) {
            if (entry.equals(row.path("entry").asString()) && scenario == row.path("scenario").intValue()) {
                JsonNode fixture = readJson(FIXTURES.resolve(row.path("file").asString()));
                assertEquals(entry, fixture.path("entry").asString(), "fixture entry mismatch");
                assertEquals(scenario, fixture.path("scenario").intValue(), "fixture scenario mismatch");
                return fixture;
            }
        }
        throw new IllegalStateException("no manifest index row for " + entry + "/s" + scenario);
    }

    private static JsonNode step(JsonNode fixture, String name) {
        for (JsonNode candidate : fixture.path("steps")) {
            if (name.equals(candidate.path("name").asString())) {
                return candidate;
            }
        }
        throw new IllegalStateException("fixture " + fixture.path("id").asString() + " has no step " + name);
    }

    // ---------------------------------------------------------------- governance: permit tracking

    /**
     * Tracks ACTIVE permits so a later cancel slice can assert the ledger returns to zero. enter()
     * acquires, finish() releases -- exactly once per request, success or failure.
     */
    private static final class PermitChain implements GovernanceChain {
        final AtomicInteger active = new AtomicInteger();
        final List<Boolean> finished = new CopyOnWriteArrayList<>();

        public GatewayDecision enter(String token, ParsedRequest request, Route target) {
            active.incrementAndGet();
            return GatewayDecision.allow("fixture-tenant", request, target);
        }

        public Optional<String> lookupCache(String key, ParsedRequest request) {
            return Optional.empty();
        }

        public void cache(String key, ParsedRequest request, String body) {
        }

        public void finish(GatewayContext context, boolean success) {
            active.decrementAndGet();
            finished.add(success);
        }

        public Map<String, Object> snapshot() {
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- pin assertions

    private static final Set<String> JSON_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "tool_calls", "thinking", "image");
    private static final Set<String> JSON_TERMINAL_KEYS =
            Set.of("object", "choices_len", "message_role", "finish_reason", "content_null");
    private static final Set<String> TEXT_KEYS = Set.of("equals");
    private static final Set<String> TOOL_CALL_PIN_KEYS = Set.of("id", "name", "arguments");
    private static final Set<String> IMAGE_KEYS = Set.of("accepted", "media_type", "sha256");
    private static final Set<String> MOCK_KEYS = Set.of("chunks");
    private static final Set<String> USAGE_KEYS = Set.of(
            "prompt_tokens", "completion_tokens", "total_tokens", "prompt_tokens_details",
            "completion_tokens_details", "absent_fields");
    private static final Set<String> PROMPT_DETAILS_KEYS = Set.of("cached_tokens");
    private static final Set<String> COMPLETION_DETAILS_KEYS = Set.of("reasoning_tokens");
    private static final Set<String> THINKING_KEYS = Set.of("equals", "before_text");

    /** Status + accounting are entry-independent; the body pins are transport-specific. */
    private static void assertExpected(String where, JsonNode expected, Result result) {
        assertExpected(where, expected, result, "chat");
    }

    private static void assertExpected(String where, JsonNode expected, Result result, String entry) {
        assertEquals(200, result.status(), where + ": controlled contract must succeed; body=" + result.body());
        assertEquals(List.of(true), result.finished(), where + ": exactly one SUCCESS finish; body=" + result.body());
        assertEquals(0, result.activePermits(), where + ": every acquired permit must be released");
        // Production AllowListHeaderPolicy strips the caller's bearer token case-insensitively; the harness
        // policy must be faithful, so pin on EVERY success drive that authorization never reaches upstream.
        Map<String, String> successPassHeaders = result.upstreamPassHeaders();
        assertNotNull(successPassHeaders, where + ": a success drive must have called the upstream");
        for (String name : successPassHeaders.keySet()) {
            assertFalse(name.equalsIgnoreCase("authorization"),
                    where + ": the caller's authorization header must never reach the upstream, got " + successPassHeaders);
        }
        String transport = pinString(expected, "transport", where);
        // Explicit entry dispatch: an unknown entry must FAIL LOUDLY, never silently fall back to the
        // chat pins (which would assert a different protocol's shape against this entry's body).
        switch (transport) {
            case "json" -> {
                JsonNode body = readTree(result.body(), where);
                switch (entry) {
                    case "chat" -> assertJsonPins(where, expected, body);
                    case "responses" -> assertResponsesJsonPins(where, expected, body);
                    case "messages" -> assertMessagesJsonPins(where, expected, body);
                    default -> throw new AssertionError(where + ": unknown entry '" + entry + "'");
                }
            }
            case "sse" -> {
                switch (entry) {
                    case "chat" -> assertSsePins(where, expected, result.body());
                    case "responses" -> assertResponsesSsePins(where, expected, result.body());
                    case "messages" -> assertMessagesSsePins(where, expected, result.body());
                    default -> throw new AssertionError(where + ": unknown entry '" + entry + "'");
                }
            }
            default -> throw new AssertionError(where + ": unknown transport pin '" + transport + "'");
        }
        // The image pin is about the REQUEST side: acceptance is only observable on the captured
        // outbound upstream body, so it is asserted here for every transport that pins it.
        JsonNode image = expected.path("image");
        if (!image.isMissingNode()) {
            assertImageAccepted(where, image, result.upstreamBody());
        }
    }

    private static void assertJsonPins(String where, JsonNode expected, JsonNode body) {
        requireKnownKeys(where, expected, JSON_EXPECTED_KEYS);
        assertTrue(body.isObject(), where + ": the entry response must be a JSON object: " + body);
        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, JSON_TERMINAL_KEYS);
        assertEquals(pinString(terminal, "object", where), body.path("object").asString(), where + ": object");
        assertEquals(pinInt(terminal, "choices_len", where), body.path("choices").size(), where + ": choices_len");
        JsonNode choice = body.path("choices").get(0);
        JsonNode message = choice.path("message");
        assertEquals(pinString(terminal, "message_role", where),
                message.path("role").asString(), where + ": message_role");
        assertEquals(pinString(terminal, "finish_reason", where),
                choice.path("finish_reason").asString(), where + ": finish_reason");
        JsonNode contentNull = terminal.path("content_null");
        if (!contentNull.isMissingNode()) {
            assertEquals(pinBool(terminal, "content_null", where), message.path("content").isNull(),
                    where + ": terminal.content_null");
        }
        JsonNode text = expected.path("text");
        // A json fixture must pin the answer shape: the literal text, or an explicitly null content.
        assertTrue(text.isObject() || contentNull.isBoolean(),
                where + ": json pins must declare text.equals or terminal.content_null");
        if (text.isObject()) {
            requireKnownKeys(where + ".text", text, TEXT_KEYS);
            assertEquals(pinString(text, "equals", where),
                    message.path("content").asString(), where + ": text.equals");
        }
        assertToolCalls(where, expected.path("tool_calls"), message.path("tool_calls"));
        JsonNode thinking = expected.path("thinking");
        if (!thinking.isMissingNode()) {
            assertThinkingCarried(where, thinking, message);
        }
        assertUsage(where, body, body.path("usage"), expected.path("usage"));
    }

    /**
     * thinking pins on a NON-STREAM body: the reasoning must be carried as native reasoning_content
     * plaintext beside the answer. A single JSON object has no frame sequence, so before_text here
     * pins that BOTH the reasoning and the answer survive in the same terminal message (carried, not
     * dropped and not merged into content -- text.equals already pins the exact answer). The frame
     * ORDER itself is observable only on the SSE transport and is pinned there by thinking.before_text
     * and sse.reasoning_before_content.
     */
    private static void assertThinkingCarried(String where, JsonNode pins, JsonNode message) {
        requireKnownKeys(where + ".thinking", pins, THINKING_KEYS);
        JsonNode reasoning = message.path("reasoning_content");
        assertTrue(reasoning.isString(),
                where + ": thinking must be carried as reasoning_content plaintext, got " + reasoning);
        assertEquals(pinString(pins, "equals", where), reasoning.asString(), where + ": thinking.equals");
        if (pinBool(pins, "before_text", where)) {
            assertTrue(message.path("content").isString(),
                    where + ": thinking.before_text requires the answer text beside the reasoning, got "
                            + message.path("content"));
        }
    }

    /**
     * Tool-call pins: the fixture's {@code arguments} is a JSON OBJECT while the wire carries a JSON
     * STRING, so the wire string is parsed and compared deeply -- a transcoder that emits the object
     * verbatim, truncates the arguments or renames the tool fails here.
     */
    private static void assertToolCalls(String where, JsonNode pins, JsonNode actual) {
        if (pins.isMissingNode()) {
            return;
        }
        assertTrue(pins.isArray(), where + ": tool_calls pins must be an array, got " + pins);
        assertTrue(actual.isArray(), where + ": the terminal message must carry tool_calls, got " + actual);
        assertEquals(pins.size(), actual.size(), where + ": tool_calls length");
        for (int i = 0; i < pins.size(); i++) {
            JsonNode pin = pins.get(i);
            String at = where + ".tool_calls[" + i + "]";
            requireKnownKeys(at, pin, TOOL_CALL_PIN_KEYS);
            JsonNode call = actual.get(i);
            assertEquals(pinString(pin, "id", at), call.path("id").asString(), at + ": id");
            assertEquals(pinString(pin, "name", at),
                    call.path("function").path("name").asString(), at + ": name");
            JsonNode arguments = call.path("function").path("arguments");
            assertTrue(arguments.isString(),
                    at + ": wire function.arguments must be a JSON string, got " + arguments);
            JsonNode pinnedArguments = pin.path("arguments");
            assertTrue(pinnedArguments.isObject(),
                    at + ": the arguments pin must be a JSON object, got " + pinnedArguments);
            assertEquals(pinnedArguments, readTree(arguments.asString(), at),
                    at + ": arguments (parsed wire string must equal the pinned object)");
        }
    }

    /** Exact token values; absent_fields MUST be absent -- an unknown count is never rendered as 0. */
    private static void assertUsage(String where, JsonNode root, JsonNode usage, JsonNode pins) {
        requireKnownKeys(where + ".usage", pins, USAGE_KEYS);
        assertTrue(usage.isObject(), where + ": usage must be an object, got " + usage);
        assertEquals(pinInt(pins, "prompt_tokens", where),
                usage.path("prompt_tokens").intValue(), where + ": usage.prompt_tokens");
        assertEquals(pinInt(pins, "completion_tokens", where),
                usage.path("completion_tokens").intValue(), where + ": usage.completion_tokens");
        assertEquals(pinInt(pins, "total_tokens", where),
                usage.path("total_tokens").intValue(), where + ": usage.total_tokens");
        JsonNode details = pins.path("prompt_tokens_details");
        if (!details.isMissingNode()) {
            requireKnownKeys(where + ".usage.prompt_tokens_details", details, PROMPT_DETAILS_KEYS);
            assertTrue(usage.path("prompt_tokens_details").isObject(),
                    where + ": prompt_tokens_details must be an object when pinned (unknown != 0)");
            assertEquals(pinInt(details, "cached_tokens", where),
                    usage.path("prompt_tokens_details").path("cached_tokens").intValue(),
                    where + ": usage.prompt_tokens_details.cached_tokens");
        }
        JsonNode completionDetails = pins.path("completion_tokens_details");
        if (!completionDetails.isMissingNode()) {
            requireKnownKeys(where + ".usage.completion_tokens_details", completionDetails, COMPLETION_DETAILS_KEYS);
            assertTrue(usage.path("completion_tokens_details").isObject(),
                    where + ": completion_tokens_details must be an object when pinned (unknown != 0)");
            assertEquals(pinInt(completionDetails, "reasoning_tokens", where),
                    usage.path("completion_tokens_details").path("reasoning_tokens").intValue(),
                    where + ": usage.completion_tokens_details.reasoning_tokens");
        }
        // absent_fields is itself a pin: when the fixture declares it, every listed path MUST be absent.
        JsonNode absent = pins.path("absent_fields");
        if (!absent.isMissingNode()) {
            assertAbsentFields(where, root, absent);
        }
    }

    private static void assertAbsentFields(String where, JsonNode root, JsonNode paths) {
        assertTrue(paths.isArray(), where + ": usage.absent_fields must be an array, got " + paths);
        for (JsonNode path : paths) {
            assertTrue(path.isString(), where + ": absent_fields entries must be string paths, got " + path);
            String dotted = path.asString();
            JsonNode node = root;
            for (String part : dotted.split("\\.")) {
                node = node.path(part);
            }
            assertTrue(node.isMissingNode(),
                    where + ": pinned-absent field must be ABSENT (unknown != 0): " + dotted + " => " + node);
        }
    }

    // ---------------------------------------------------------------- responses JSON pins (non-stream)

    private static final Set<String> RESPONSES_JSON_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "tool_calls", "thinking", "image");
    private static final Set<String> RESPONSES_JSON_TERMINAL_KEYS =
            Set.of("object", "status", "output_item_types", "function_call", "reasoning_encrypted_content_prefix");
    private static final Set<String> RESPONSES_FUNCTION_CALL_KEYS = Set.of("call_id", "name", "arguments");
    private static final Set<String> RESPONSES_USAGE_KEYS = Set.of(
            "input_tokens", "output_tokens", "total_tokens", "input_tokens_details",
            "output_tokens_details", "absent_fields");
    private static final Set<String> RESPONSES_INPUT_DETAILS_KEYS = Set.of("cached_tokens");
    private static final Set<String> RESPONSES_OUTPUT_DETAILS_KEYS = Set.of("reasoning_tokens");

    private static void assertResponsesJsonPins(String where, JsonNode expected, JsonNode body) {
        requireKnownKeys(where, expected, RESPONSES_JSON_EXPECTED_KEYS);
        assertTrue(body.isObject(), where + ": the responses entry body must be a JSON object: " + body);
        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, RESPONSES_JSON_TERMINAL_KEYS);
        assertEquals(pinString(terminal, "object", where), body.path("object").asString(), where + ": object");
        assertEquals(pinString(terminal, "status", where), body.path("status").asString(), where + ": status");
        JsonNode output = body.path("output");
        assertTrue(output.isArray(), where + ": output must be an array, got " + output);
        JsonNode pinnedTypes = terminal.path("output_item_types");
        assertTrue(pinnedTypes.isArray(), where + ": output_item_types pin must be an array");
        List<String> actualTypes = new ArrayList<>();
        for (JsonNode item : output) {
            actualTypes.add(item.path("type").asString());
        }
        List<String> expectedTypes = new ArrayList<>();
        for (JsonNode t : pinnedTypes) {
            expectedTypes.add(t.asString());
        }
        assertEquals(expectedTypes, actualTypes, where + ": output_item_types");
        // terminal.function_call pin (s3): the function_call output item; arguments is a JSON STRING.
        JsonNode functionCall = terminal.path("function_call");
        if (!functionCall.isMissingNode()) {
            assertResponsesFunctionCall(where + ".terminal.function_call", functionCall, output);
        }
        // Top-level tool_calls pin: the function_call output items in {id,name,arguments(object)} shape.
        JsonNode toolCalls = expected.path("tool_calls");
        if (!toolCalls.isMissingNode()) {
            assertResponsesToolCalls(where, toolCalls, output);
        }
        // The text pin is CONDITIONAL: a function_call-only turn (s3 initial) carries no answer text,
        // so it declares no text pin. When present it stays strict (exact literal).
        JsonNode text = expected.path("text");
        // A responses json fixture must pin the answer shape: literal text, or tool_calls/function_call.
        assertTrue(text.isObject() || !toolCalls.isMissingNode() || !functionCall.isMissingNode(),
                where + ": responses json pins must declare text.equals or tool_calls/function_call");
        if (!text.isMissingNode()) {
            requireKnownKeys(where + ".text", text, TEXT_KEYS);
            assertEquals(pinString(text, "equals", where), responsesOutputText(output), where + ": text.equals");
        }
        // s4 reasoning pins: locate the reasoning output item (plaintext reasoning_text + minted envelope).
        // thinking.equals reads the reasoning_text literal (independent fixture truth, never recomputed);
        // before_text pins the reasoning item BEFORE the message item in output[]; the terminal prefix pin
        // checks the minted continuation envelope carries the llmgw1: namespace.
        JsonNode thinking = expected.path("thinking");
        JsonNode encryptedPrefixPin = terminal.path("reasoning_encrypted_content_prefix");
        if (!thinking.isMissingNode() || !encryptedPrefixPin.isMissingNode()) {
            int reasoningAt = -1;
            int messageAt = -1;
            JsonNode reasoningItem = null;
            for (int i = 0; i < output.size(); i++) {
                JsonNode item = output.get(i);
                String type = item.path("type").asString();
                if ("reasoning".equals(type)) {
                    assertTrue(reasoningAt < 0, where + ": exactly one reasoning output item");
                    reasoningAt = i;
                    reasoningItem = item;
                } else if ("message".equals(type) && messageAt < 0) {
                    messageAt = i;
                }
            }
            assertTrue(reasoningItem != null, where + ": the output must carry a reasoning item, got " + output);
            if (!thinking.isMissingNode()) {
                requireKnownKeys(where + ".thinking", thinking, THINKING_KEYS);
                StringBuilder reasoningText = new StringBuilder();
                for (JsonNode part : reasoningItem.path("content")) {
                    if ("reasoning_text".equals(part.path("type").asString())) {
                        reasoningText.append(part.path("text").asString());
                    }
                }
                assertEquals(pinString(thinking, "equals", where), reasoningText.toString(),
                        where + ": thinking.equals");
                boolean beforeText = pinBool(thinking, "before_text", where);
                assertEquals(beforeText, messageAt >= 0 && reasoningAt < messageAt,
                        where + ": thinking.before_text (reasoningAt=" + reasoningAt + ", messageAt=" + messageAt + ")");
            }
            if (!encryptedPrefixPin.isMissingNode()) {
                String prefix = pinString(terminal, "reasoning_encrypted_content_prefix", where);
                JsonNode encrypted = reasoningItem.path("encrypted_content");
                assertTrue(encrypted.isString() && encrypted.asString().startsWith(prefix),
                        where + ": reasoning encrypted_content must start with '" + prefix + "', got " + encrypted);
            }
        }
        assertResponsesUsage(where, body, body.path("usage"), expected.path("usage"));
    }

    /**
     * The responses entry expresses a tool call as a {@code function_call} output item (not a chat
     * message.tool_calls). The terminal pin's {@code arguments} is a JSON STRING, mirrored by the wire
     * item; both are parsed and compared deeply so a transcoder that truncates or renames fails here.
     */
    private static void assertResponsesFunctionCall(String where, JsonNode pin, JsonNode output) {
        requireKnownKeys(where, pin, RESPONSES_FUNCTION_CALL_KEYS);
        JsonNode item = null;
        for (JsonNode candidate : output) {
            if ("function_call".equals(candidate.path("type").asString())) {
                item = candidate;
                break;
            }
        }
        assertTrue(item != null, where + ": the output must carry a function_call item, got " + output);
        assertEquals(pinString(pin, "call_id", where), item.path("call_id").asString(), where + ": call_id");
        assertEquals(pinString(pin, "name", where), item.path("name").asString(), where + ": name");
        JsonNode arguments = item.path("arguments");
        assertTrue(arguments.isString(),
                where + ": function_call.arguments must be a JSON string, got " + arguments);
        assertEquals(readTree(pinString(pin, "arguments", where), where),
                readTree(arguments.asString(), where), where + ": arguments (parsed wire string)");
    }

    /**
     * Top-level tool_calls pin on the responses entry: each {id,name,arguments} maps to a function_call
     * output item (id&larr;call_id). The pin's {@code arguments} is a JSON OBJECT while the wire item
     * carries a JSON STRING, so the wire string is parsed and compared deeply (mirrors chat tool_calls).
     */
    private static void assertResponsesToolCalls(String where, JsonNode pins, JsonNode output) {
        assertTrue(pins.isArray(), where + ": tool_calls pins must be an array, got " + pins);
        List<JsonNode> calls = new ArrayList<>();
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asString())) {
                calls.add(item);
            }
        }
        assertEquals(pins.size(), calls.size(), where + ": tool_calls length");
        for (int i = 0; i < pins.size(); i++) {
            JsonNode pin = pins.get(i);
            String at = where + ".tool_calls[" + i + "]";
            requireKnownKeys(at, pin, TOOL_CALL_PIN_KEYS);
            JsonNode call = calls.get(i);
            assertEquals(pinString(pin, "id", at), call.path("call_id").asString(), at + ": id (call_id)");
            assertEquals(pinString(pin, "name", at), call.path("name").asString(), at + ": name");
            JsonNode arguments = call.path("arguments");
            assertTrue(arguments.isString(),
                    at + ": function_call.arguments must be a JSON string, got " + arguments);
            JsonNode pinnedArguments = pin.path("arguments");
            assertTrue(pinnedArguments.isObject(),
                    at + ": the arguments pin must be a JSON object, got " + pinnedArguments);
            assertEquals(pinnedArguments, readTree(arguments.asString(), at),
                    at + ": arguments (parsed wire string must equal the pinned object)");
        }
    }

    /** Concatenate every output_text part across all message items in the responses output array. */
    private static String responsesOutputText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asString())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asString())) {
                    text.append(part.path("text").asString());
                }
            }
        }
        return text.toString();
    }

    private static void assertResponsesUsage(String where, JsonNode root, JsonNode usage, JsonNode pins) {
        requireKnownKeys(where + ".usage", pins, RESPONSES_USAGE_KEYS);
        assertTrue(usage.isObject(), where + ": usage must be an object, got " + usage);
        assertEquals(pinInt(pins, "input_tokens", where),
                usage.path("input_tokens").intValue(), where + ": usage.input_tokens");
        assertEquals(pinInt(pins, "output_tokens", where),
                usage.path("output_tokens").intValue(), where + ": usage.output_tokens");
        assertEquals(pinInt(pins, "total_tokens", where),
                usage.path("total_tokens").intValue(), where + ": usage.total_tokens");
        JsonNode details = pins.path("input_tokens_details");
        if (!details.isMissingNode()) {
            requireKnownKeys(where + ".usage.input_tokens_details", details, RESPONSES_INPUT_DETAILS_KEYS);
            assertTrue(usage.path("input_tokens_details").isObject(),
                    where + ": input_tokens_details must be an object when pinned (unknown != 0)");
            assertEquals(pinInt(details, "cached_tokens", where),
                    usage.path("input_tokens_details").path("cached_tokens").intValue(),
                    where + ": usage.input_tokens_details.cached_tokens");
        }
        JsonNode outputDetails = pins.path("output_tokens_details");
        if (!outputDetails.isMissingNode()) {
            requireKnownKeys(where + ".usage.output_tokens_details", outputDetails, RESPONSES_OUTPUT_DETAILS_KEYS);
            assertTrue(usage.path("output_tokens_details").isObject(),
                    where + ": output_tokens_details must be an object when pinned (unknown != 0)");
            assertEquals(pinInt(outputDetails, "reasoning_tokens", where),
                    usage.path("output_tokens_details").path("reasoning_tokens").intValue(),
                    where + ": usage.output_tokens_details.reasoning_tokens");
        }
        JsonNode absent = pins.path("absent_fields");
        if (!absent.isMissingNode()) {
            assertAbsentFields(where, root, absent);
        }
    }

    // ---------------------------------------------------------------- responses SSE pins (stream)

    private static final Set<String> RESPONSES_SSE_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "sse", "thinking");
    private static final Set<String> RESPONSES_SSE_TERMINAL_KEYS = Set.of(
            "event", "count", "response_status", "output_item_types", "sequence_number",
            "done_marker", "usage_location");
    private static final Set<String> RESPONSES_SSE_KEYS = Set.of(
            "framing", "event_type_equals_data_type", "sequence_number", "preamble",
            "message_item", "reasoning_item", "terminal", "done_marker");
    private static final Set<String> RESPONSES_SSE_MESSAGE_ITEM_KEYS =
            Set.of("open", "delta_event", "done_text", "close");
    private static final Set<String> RESPONSES_SSE_REASONING_ITEM_KEYS =
            Set.of("open", "delta_event", "done_text", "close", "encrypted_content_prefix", "summary_empty");
    private static final Set<String> RESPONSES_SSE_TERMINAL_SUB_KEYS =
            Set.of("event", "count", "response_status", "usage_location");

    /**
     * Responses event+data SSE pins: every frame carries an {@code event:} line whose type equals the
     * data payload's {@code type}; sequence_number is contiguous from 0 (stricter than the fixture's
     * "monotonic_from_0", safe for the deterministic mock); the preamble, message-item lifecycle and
     * exactly-one completed terminal are pinned; NO [DONE] sentinel (done_marker:false).
     * Usage lives at {@code response.usage} inside the completed event.
     */
    private static void assertResponsesSsePins(String where, JsonNode expected, String body) {
        requireKnownKeys(where, expected, RESPONSES_SSE_EXPECTED_KEYS);
        List<SseFrame> frames = parseFrames(body);
        assertFalse(frames.isEmpty(), where + ": the stream must carry frames; body=" + body);

        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, RESPONSES_SSE_TERMINAL_KEYS);
        JsonNode sse = expected.path("sse");
        requireKnownKeys(where + ".sse", sse, RESPONSES_SSE_KEYS);

        // Framing: event+data. Each frame must have exactly one event: line and one data: line.
        assertEquals("event+data", pinString(sse, "framing", where),
                where + ": only event+data framing is implemented for responses");
        boolean eventTypeEqualsDataType = pinBool(sse, "event_type_equals_data_type", where);
        List<JsonNode> payloads = new ArrayList<>();
        List<String> eventTypes = new ArrayList<>();
        for (SseFrame frame : frames) {
            String eventType = null;
            int eventLines = 0;
            int dataLines = 0;
            for (String line : frame.lines()) {
                if (line.startsWith("event:")) {
                    eventLines++;
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataLines++;
                } else {
                    throw new AssertionError(where + ": event+data framing violated by line '" + line + "'");
                }
            }
            assertEquals(1, eventLines, where + ": each frame must carry exactly one event: line");
            assertEquals(1, dataLines, where + ": each frame must carry exactly one data: line");
            JsonNode payload = readTree(frame.data(), where);
            assertTrue(payload.isObject(), where + ": each data payload must be a JSON object");
            if (eventTypeEqualsDataType) {
                assertEquals(eventType, payload.path("type").asString(),
                        where + ": event line type must equal data payload type");
            }
            payloads.add(payload);
            eventTypes.add(eventType);
        }

        // sequence_number: monotonic from 0 across ALL frames.
        assertEquals("monotonic_from_0", pinString(sse, "sequence_number", where),
                where + ": only monotonic_from_0 sequence_number is implemented");
        assertEquals("monotonic_from_0", pinString(terminal, "sequence_number", where),
                where + ": terminal.sequence_number must agree");
        for (int i = 0; i < payloads.size(); i++) {
            JsonNode seq = payloads.get(i).path("sequence_number");
            assertTrue(seq.isIntegralNumber(),
                    where + ": frame " + i + " must carry an integral sequence_number, got " + seq);
            assertEquals(i, seq.intValue(), where + ": sequence_number must be monotonic from 0 at frame " + i);
        }

        // done_marker:false → NO [DONE] sentinel anywhere.
        assertFalse(pinBool(sse, "done_marker", where), where + ": only done_marker=false is implemented");
        assertFalse(pinBool(terminal, "done_marker", where), where + ": terminal.done_marker must agree");
        assertEquals(0, count(body, "[DONE]"), where + ": responses SSE must NOT carry a [DONE] sentinel");

        // Preamble: the first frames must be the pinned event types in order.
        JsonNode preamble = sse.path("preamble");
        assertTrue(preamble.isArray(), where + ": sse.preamble must be an array");
        for (int i = 0; i < preamble.size(); i++) {
            assertEquals(preamble.get(i).asString(), eventTypes.get(i),
                    where + ": preamble[" + i + "] event type");
        }

        // Message-item lifecycle: open → delta(s) concatenating to done_text → close.
        JsonNode messageItem = sse.path("message_item");
        requireKnownKeys(where + ".sse.message_item", messageItem, RESPONSES_SSE_MESSAGE_ITEM_KEYS);
        String openEvent = pinString(messageItem, "open", where);
        String deltaEvent = pinString(messageItem, "delta_event", where);
        String doneText = pinString(messageItem, "done_text", where);
        String closeEvent = pinString(messageItem, "close", where);

        int openAt = -1;
        int closeAt = -1;
        int firstMessageDeltaAt = -1;
        StringBuilder deltaText = new StringBuilder();
        String doneEventText = null;
        for (int i = 0; i < eventTypes.size(); i++) {
            String type = eventTypes.get(i);
            JsonNode payload = payloads.get(i);
            if (openEvent.equals(type) && "message".equals(payload.path("item").path("type").asString())) {
                assertTrue(openAt < 0, where + ": exactly one message-item open event");
                openAt = i;
            } else if (deltaEvent.equals(type)) {
                deltaText.append(payload.path("delta").asString());
                if (firstMessageDeltaAt < 0) {
                    firstMessageDeltaAt = i;
                }
            } else if ("response.output_text.done".equals(type)) {
                doneEventText = payload.path("text").asString();
            } else if (closeEvent.equals(type) && "message".equals(payload.path("item").path("type").asString())) {
                assertTrue(closeAt < 0, where + ": exactly one message-item close event");
                closeAt = i;
            }
        }
        assertTrue(openAt >= 0, where + ": the stream must carry the message-item open event '" + openEvent + "'");
        assertTrue(closeAt > openAt, where + ": the message-item close must follow the open");
        assertEquals(doneText, deltaText.toString(), where + ": concatenated deltas must equal done_text");
        assertEquals(doneText, doneEventText, where + ": output_text.done must carry the full text");

        // Reasoning-item lifecycle (s4): open → reasoning delta(s) concatenating to done_text → close,
        // with the minted continuation envelope and an empty summary on the closed item. Conditional:
        // only the thinking scenario pins a reasoning_item; text-only streams (s2) declare none.
        StringBuilder reasoningDeltaText = new StringBuilder();
        int reasoningFirstDeltaAt = -1;
        int reasoningCloseAt = -1;
        JsonNode reasoningItem = sse.path("reasoning_item");
        if (!reasoningItem.isMissingNode()) {
            requireKnownKeys(where + ".sse.reasoning_item", reasoningItem, RESPONSES_SSE_REASONING_ITEM_KEYS);
            String reasoningOpenEvent = pinString(reasoningItem, "open", where);
            String reasoningDeltaEvent = pinString(reasoningItem, "delta_event", where);
            String reasoningDoneText = pinString(reasoningItem, "done_text", where);
            String reasoningCloseEvent = pinString(reasoningItem, "close", where);
            String encryptedPrefix = pinString(reasoningItem, "encrypted_content_prefix", where);
            boolean summaryEmpty = pinBool(reasoningItem, "summary_empty", where);
            int reasoningOpenAt = -1;
            String reasoningDoneEventText = null;
            String reasoningEncryptedContent = null;
            JsonNode reasoningSummary = null;
            for (int i = 0; i < eventTypes.size(); i++) {
                String type = eventTypes.get(i);
                JsonNode payload = payloads.get(i);
                if (reasoningOpenEvent.equals(type) && "reasoning".equals(payload.path("item").path("type").asString())) {
                    assertTrue(reasoningOpenAt < 0, where + ": exactly one reasoning-item open event");
                    reasoningOpenAt = i;
                } else if (reasoningDeltaEvent.equals(type)) {
                    reasoningDeltaText.append(payload.path("delta").asString());
                    if (reasoningFirstDeltaAt < 0) {
                        reasoningFirstDeltaAt = i;
                    }
                } else if ("response.reasoning_text.done".equals(type)) {
                    reasoningDoneEventText = payload.path("text").asString();
                } else if (reasoningCloseEvent.equals(type) && "reasoning".equals(payload.path("item").path("type").asString())) {
                    assertTrue(reasoningCloseAt < 0, where + ": exactly one reasoning-item close event");
                    reasoningCloseAt = i;
                    reasoningEncryptedContent = payload.path("item").path("encrypted_content").asString();
                    reasoningSummary = payload.path("item").path("summary");
                }
            }
            assertTrue(reasoningOpenAt >= 0,
                    where + ": the stream must carry the reasoning-item open event '" + reasoningOpenEvent + "'");
            assertTrue(reasoningCloseAt > reasoningOpenAt, where + ": the reasoning-item close must follow the open");
            assertEquals(reasoningDoneText, reasoningDeltaText.toString(),
                    where + ": concatenated reasoning deltas must equal done_text");
            assertEquals(reasoningDoneText, reasoningDoneEventText,
                    where + ": reasoning_text.done must carry the full text");
            assertTrue(reasoningEncryptedContent != null && reasoningEncryptedContent.startsWith(encryptedPrefix),
                    where + ": reasoning item encrypted_content must start with '" + encryptedPrefix
                            + "', got " + reasoningEncryptedContent);
            assertTrue(reasoningSummary != null && reasoningSummary.isArray(),
                    where + ": reasoning item summary must be an array, got " + reasoningSummary);
            assertEquals(summaryEmpty, reasoningSummary.isEmpty(), where + ": reasoning_item.summary_empty");
        }

        // thinking pins (independent truth): the concatenated reasoning literal plus its frame order
        // relative to the answer text. before_text requires the reasoning delta AND close to precede
        // the message text delta AND open (a codec that interleaves or postpones reasoning fails).
        JsonNode thinking = expected.path("thinking");
        if (!thinking.isMissingNode()) {
            requireKnownKeys(where + ".thinking", thinking, THINKING_KEYS);
            assertEquals(pinString(thinking, "equals", where), reasoningDeltaText.toString(),
                    where + ": thinking.equals");
            boolean beforeText = pinBool(thinking, "before_text", where);
            assertEquals(beforeText,
                    reasoningFirstDeltaAt >= 0 && firstMessageDeltaAt >= 0 && reasoningFirstDeltaAt < firstMessageDeltaAt,
                    where + ": thinking.before_text (reasoningDeltaAt=" + reasoningFirstDeltaAt
                            + ", messageDeltaAt=" + firstMessageDeltaAt + ")");
            assertEquals(beforeText,
                    reasoningCloseAt >= 0 && openAt >= 0 && reasoningCloseAt < openAt,
                    where + ": thinking.before_text requires the reasoning close before the message open"
                            + " (reasoningCloseAt=" + reasoningCloseAt + ", messageOpenAt=" + openAt + ")");
        }

        // text.equals pin (independent of done_text: the fixture is the truth).
        JsonNode text = expected.path("text");
        requireKnownKeys(where + ".text", text, TEXT_KEYS);
        assertEquals(pinString(text, "equals", where), deltaText.toString(), where + ": text.equals");

        // Terminal: exactly one completed event, pinned count, response_status, output_item_types,
        // usage_location. The terminal is the LAST frame.
        JsonNode sseTerminal = sse.path("terminal");
        requireKnownKeys(where + ".sse.terminal", sseTerminal, RESPONSES_SSE_TERMINAL_SUB_KEYS);
        String terminalEvent = pinString(terminal, "event", where);
        assertEquals(terminalEvent, pinString(sseTerminal, "event", where), where + ": sse.terminal.event must agree");
        int terminalCount = pinInt(terminal, "count", where);
        assertEquals(terminalCount, pinInt(sseTerminal, "count", where), where + ": sse.terminal.count must agree");
        List<Integer> terminalIndices = new ArrayList<>();
        for (int i = 0; i < eventTypes.size(); i++) {
            if (terminalEvent.equals(eventTypes.get(i))) {
                terminalIndices.add(i);
            }
        }
        assertEquals(terminalCount, terminalIndices.size(), where + ": exactly " + terminalCount + " terminal event(s)");
        int terminalAt = terminalIndices.get(terminalIndices.size() - 1);
        assertEquals(frames.size() - 1, terminalAt, where + ": the terminal must be the LAST frame");
        JsonNode terminalPayload = payloads.get(terminalAt);
        JsonNode response = terminalPayload.path("response");
        assertTrue(response.isObject(), where + ": the terminal must embed a response object");
        assertEquals(pinString(terminal, "response_status", where), response.path("status").asString(),
                where + ": terminal.response_status");
        assertEquals(pinString(sseTerminal, "response_status", where), response.path("status").asString(),
                where + ": sse.terminal.response_status must agree");
        JsonNode pinnedTypes = terminal.path("output_item_types");
        assertTrue(pinnedTypes.isArray(), where + ": terminal.output_item_types must be an array");
        JsonNode output = response.path("output");
        assertTrue(output.isArray(), where + ": terminal response.output must be an array");
        List<String> actualTypes = new ArrayList<>();
        for (JsonNode item : output) {
            actualTypes.add(item.path("type").asString());
        }
        List<String> expectedTypes = new ArrayList<>();
        for (JsonNode t : pinnedTypes) {
            expectedTypes.add(t.asString());
        }
        assertEquals(expectedTypes, actualTypes, where + ": terminal.output_item_types");

        // Usage at response.usage inside the completed event.
        assertEquals("response.usage", pinString(terminal, "usage_location", where),
                where + ": only response.usage location is implemented");
        assertEquals("response.usage", pinString(sseTerminal, "usage_location", where),
                where + ": sse.terminal.usage_location must agree");
        assertResponsesUsage(where, response, response.path("usage"), expected.path("usage"));
    }

    // ---------------------------------------------------------------- messages JSON pins (non-stream)

    private static final Set<String> MESSAGES_JSON_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "tool_calls", "thinking", "image");
    private static final Set<String> MESSAGES_JSON_TERMINAL_KEYS =
            Set.of("type", "role", "stop_reason", "stop_sequence_null", "content_block_types",
                    "thinking_signature_prefix");
    /**
     * The Anthropic usage shape is NOT the responses one: input_tokens is the cache MISS, the hit is
     * cache_read_input_tokens, cache_creation_input_tokens is a known constant 0, and there is no
     * total_tokens. output_tokens_details.thinking_tokens (s4) is pinned only when the fixture declares
     * it; when absent the wire must NOT invent it (an unknown reasoning count is never rendered as 0).
     */
    private static final Set<String> MESSAGES_USAGE_KEYS = Set.of(
            "input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens",
            "output_tokens", "output_tokens_details", "absent_fields");
    private static final Set<String> MESSAGES_OUTPUT_DETAILS_KEYS = Set.of("thinking_tokens");

    /**
     * Anthropic Messages non-stream pins: the terminal is a {@code message} object whose answer lives in
     * {@code content[]} BLOCKS (not a top-level content string), so text.equals reads the text blocks.
     * Every literal comes from the versioned fixture, never recomputed from gateway code.
     */
    private static void assertMessagesJsonPins(String where, JsonNode expected, JsonNode body) {
        requireKnownKeys(where, expected, MESSAGES_JSON_EXPECTED_KEYS);
        assertTrue(body.isObject(), where + ": the messages entry body must be a JSON object: " + body);
        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, MESSAGES_JSON_TERMINAL_KEYS);
        assertEquals(pinString(terminal, "type", where), body.path("type").asString(), where + ": type");
        assertEquals(pinString(terminal, "role", where), body.path("role").asString(), where + ": role");
        assertEquals(pinString(terminal, "stop_reason", where),
                body.path("stop_reason").asString(), where + ": stop_reason");
        assertEquals(pinBool(terminal, "stop_sequence_null", where), body.path("stop_sequence").isNull(),
                where + ": terminal.stop_sequence_null");
        JsonNode content = body.path("content");
        assertTrue(content.isArray(), where + ": content must be an array of blocks, got " + content);
        JsonNode pinnedTypes = terminal.path("content_block_types");
        assertTrue(pinnedTypes.isArray(), where + ": content_block_types pin must be an array");
        List<String> actualTypes = new ArrayList<>();
        for (JsonNode block : content) {
            actualTypes.add(block.path("type").asString());
        }
        List<String> expectedTypes = new ArrayList<>();
        for (JsonNode t : pinnedTypes) {
            expectedTypes.add(t.asString());
        }
        assertEquals(expectedTypes, actualTypes, where + ": content_block_types (order included)");
        // terminal.thinking_signature_prefix (s4): the output thinking block's signature must carry the
        // gateway continuation envelope namespace (llmgw1:). Only asserted when pinned; s1/s2/s3 declare
        // none, so a thinking-less terminal is not forced to invent a signature.
        JsonNode signaturePrefix = terminal.path("thinking_signature_prefix");
        if (!signaturePrefix.isMissingNode()) {
            String prefix = pinString(terminal, "thinking_signature_prefix", where);
            boolean signed = false;
            for (JsonNode block : content) {
                if ("thinking".equals(block.path("type").asString())) {
                    JsonNode signature = block.path("signature");
                    assertTrue(signature.isString(),
                            where + ": the thinking block must carry a string signature, got " + signature);
                    assertTrue(signature.asString().startsWith(prefix),
                            where + ": terminal.thinking_signature_prefix '" + prefix + "', got " + signature);
                    signed = true;
                }
            }
            assertTrue(signed, where + ": thinking_signature_prefix requires a thinking block in content[]");
        }
        JsonNode text = expected.path("text");
        if (!text.isMissingNode()) {
            requireKnownKeys(where + ".text", text, TEXT_KEYS);
            assertEquals(pinString(text, "equals", where), messagesContentText(content), where + ": text.equals");
        } else {
            // A json fixture must pin the answer shape: text.equals OR tool_calls (mirrors the chat
            // discipline). A tool_use terminal with neither pin would pass vacuously.
            assertTrue(!expected.path("tool_calls").isMissingNode(),
                    where + ": messages json pins must declare text.equals or tool_calls");
        }
        assertMessagesToolCalls(where, expected.path("tool_calls"), content);
        JsonNode thinking = expected.path("thinking");
        if (!thinking.isMissingNode()) {
            assertMessagesThinking(where, thinking, content);
        }
        assertMessagesUsage(where, body, body.path("usage"), expected.path("usage"));
    }

    /**
     * thinking pins on the messages NON-STREAM body: the reasoning lives in a {@code thinking} content
     * block (plaintext in its {@code thinking} field), NOT merged into the text blocks. thinking.equals
     * reads that block's plaintext against the fixture literal; before_text pins that the thinking block
     * precedes the text block in content[] order (matrix §6.4). A single JSON object has no frame
     * sequence, so the block ORDER is the observable here (the SSE transport pins the frame order too).
     * Every literal comes from the versioned fixture, never recomputed from gateway code.
     */
    private static void assertMessagesThinking(String where, JsonNode pins, JsonNode content) {
        requireKnownKeys(where + ".thinking", pins, THINKING_KEYS);
        int thinkingIndex = -1;
        int textIndex = -1;
        StringBuilder thinking = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonNode block = content.get(i);
            String type = block.path("type").asString();
            if ("thinking".equals(type)) {
                assertTrue(thinkingIndex < 0, where + ": exactly one thinking block, got a second at " + i);
                thinkingIndex = i;
                thinking.append(block.path("thinking").asString());
            } else if ("text".equals(type) && textIndex < 0) {
                textIndex = i;
            }
        }
        assertTrue(thinkingIndex >= 0, where + ": the content must carry a thinking block");
        assertEquals(pinString(pins, "equals", where), thinking.toString(), where + ": thinking.equals");
        if (pinBool(pins, "before_text", where)) {
            assertTrue(textIndex >= 0,
                    where + ": thinking.before_text requires a text block beside the thinking, got none");
            assertTrue(thinkingIndex < textIndex,
                    where + ": thinking.before_text (thinking block " + thinkingIndex
                            + " must precede text block " + textIndex + ")");
        }
    }

    /** Concatenate every {@code text} block's text across the messages content array, in block order. */
    private static String messagesContentText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString())) {
                text.append(block.path("text").asString());
            }
        }
        return text.toString();
    }

    /**
     * Tool-call pins on the messages entry: the answer lives in {@code content[]} blocks, so each
     * {@code tool_use} block is compared against the fixture's {@code tool_calls} literal IN BLOCK ORDER.
     * The fixture {@code arguments} is a JSON OBJECT and the wire {@code tool_use.input} is the same
     * object, so they are compared deeply -- a transcoder that renames the tool, drops the id or reshapes
     * the input fails here. A fixture that pins no tool_calls must see no tool_use block (must-not-invent),
     * so an unchecked tool_use can never pass silently. Every literal comes from the versioned fixture.
     */
    private static void assertMessagesToolCalls(String where, JsonNode pins, JsonNode content) {
        List<JsonNode> toolUse = new ArrayList<>();
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asString())) {
                toolUse.add(block);
            }
        }
        if (pins.isMissingNode()) {
            assertTrue(toolUse.isEmpty(),
                    where + ": a tool_use block must be pinned by tool_calls, got " + toolUse.size() + " unpinned");
            return;
        }
        assertTrue(pins.isArray(), where + ": tool_calls pins must be an array, got " + pins);
        assertEquals(pins.size(), toolUse.size(), where + ": tool_calls length (one tool_use block each)");
        for (int i = 0; i < pins.size(); i++) {
            JsonNode pin = pins.get(i);
            JsonNode block = toolUse.get(i);
            String at = where + ".tool_calls[" + i + "]";
            requireKnownKeys(at, pin, TOOL_CALL_PIN_KEYS);
            assertEquals(pinString(pin, "id", where), block.path("id").asString(), at + ": id preserved");
            assertEquals(pinString(pin, "name", where), block.path("name").asString(), at + ": name preserved");
            JsonNode arguments = pin.path("arguments");
            assertTrue(arguments.isObject(), at + ": fixture arguments must be a JSON object, got " + arguments);
            assertTrue(block.path("input").isObject(),
                    at + ": tool_use.input must be a JSON object, got " + block.path("input"));
            assertEquals(arguments, block.path("input"), at + ": arguments (input object) preserved");
        }
    }

    /** Exact Anthropic token values; absent_fields MUST be absent -- an unknown count is never 0. */
    private static void assertMessagesUsage(String where, JsonNode root, JsonNode usage, JsonNode pins) {
        requireKnownKeys(where + ".usage", pins, MESSAGES_USAGE_KEYS);
        assertTrue(usage.isObject(), where + ": usage must be an object, got " + usage);
        assertMessagesCount(where, usage, pins, "input_tokens");
        assertMessagesCount(where, usage, pins, "cache_read_input_tokens");
        assertMessagesCount(where, usage, pins, "cache_creation_input_tokens");
        assertMessagesCount(where, usage, pins, "output_tokens");
        // output_tokens_details.thinking_tokens (s4): pinned only when the fixture declares it; when the
        // fixture pins none the wire must NOT invent it (an unknown reasoning count is never 0). The
        // deep compare reads the PRESENT integer, so a dropped thinking_tokens cannot pass a pinned 2.
        JsonNode details = pins.path("output_tokens_details");
        if (!details.isMissingNode()) {
            requireKnownKeys(where + ".usage.output_tokens_details", details, MESSAGES_OUTPUT_DETAILS_KEYS);
            assertTrue(usage.path("output_tokens_details").isObject(),
                    where + ": output_tokens_details must be an object when pinned (unknown != 0), got "
                            + usage.path("output_tokens_details"));
            assertMessagesCount(where + ".usage.output_tokens_details",
                    usage.path("output_tokens_details"), details, "thinking_tokens");
        } else {
            assertTrue(usage.path("output_tokens_details").isMissingNode(),
                    where + ": output_tokens_details must not be invented when the fixture pins none, got "
                            + usage.path("output_tokens_details"));
        }
        JsonNode absent = pins.path("absent_fields");
        if (!absent.isMissingNode()) {
            assertAbsentFields(where, root, absent);
        }
    }

    /**
     * A pinned count must be a PRESENT integer on the wire: {@code path().intValue()} renders a missing
     * field as 0, which would let a dropped cache_creation_input_tokens pass a pinned 0 vacuously.
     */
    private static void assertMessagesCount(String where, JsonNode usage, JsonNode pins, String field) {
        JsonNode actual = usage.path(field);
        assertTrue(actual.isIntegralNumber(),
                where + ": usage." + field + " must be a present integer (absent != 0), got " + actual);
        assertEquals(pinInt(pins, field, where), actual.intValue(), where + ": usage." + field);
    }

    // ---------------------------------------------------------------- messages SSE pins (stream)

    private static final Set<String> MESSAGES_SSE_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "sse", "thinking");
    private static final Set<String> MESSAGES_SSE_TERMINAL_KEYS = Set.of(
            "message_stop_count", "stop_reason_event", "stop_reason", "done_marker",
            "usage_location", "block_indices");
    private static final Set<String> MESSAGES_SSE_KEYS = Set.of(
            "framing", "event_type_equals_data_type", "preamble", "message_start_usage",
            "block_indices", "text_block", "thinking_block", "terminal", "done_marker");
    private static final Set<String> MESSAGES_SSE_START_USAGE_KEYS = Set.of("input_tokens", "output_tokens");
    private static final Set<String> MESSAGES_SSE_TEXT_BLOCK_KEYS =
            Set.of("start_type", "delta_type", "delta_concat", "closed");
    private static final Set<String> MESSAGES_SSE_THINKING_BLOCK_KEYS =
            Set.of("start_type", "delta_type", "delta_concat", "signature_delta_prefix", "closed");
    private static final Set<String> MESSAGES_SSE_TERMINAL_SUB_KEYS =
            Set.of("message_delta_stop_reason", "message_stop_count", "usage_location");

    /**
     * Anthropic Messages event+data SSE pins: every frame carries an {@code event:} line whose type equals
     * the data payload's {@code type}; the preamble is message_start (whose SDK-required usage is the
     * initial placeholder); content block indices are contiguous from 0; the text block opens, streams
     * text_delta frames concatenating to the pinned literal and closes; the terminal is exactly one
     * message_delta (stop_reason + the OVERWRITING final usage) followed by message_stop as the LAST
     * frame; and there is NO [DONE] sentinel (messages ends at message_stop).
     *
     * <p>thinking_block is whitelisted and asserted (s4); tool_use SSE pins are deliberately NOT
     * whitelisted yet, so an unimplemented pin FAILS instead of being silently skipped.
     */
    private static void assertMessagesSsePins(String where, JsonNode expected, String body) {
        requireKnownKeys(where, expected, MESSAGES_SSE_EXPECTED_KEYS);
        List<SseFrame> frames = parseFrames(body);
        assertFalse(frames.isEmpty(), where + ": the stream must carry frames; body=" + body);

        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, MESSAGES_SSE_TERMINAL_KEYS);
        JsonNode sse = expected.path("sse");
        requireKnownKeys(where + ".sse", sse, MESSAGES_SSE_KEYS);

        // Framing: event+data. Each frame must have exactly one event: line and one data: line.
        assertEquals("event+data", pinString(sse, "framing", where),
                where + ": only event+data framing is implemented for messages");
        boolean eventTypeEqualsDataType = pinBool(sse, "event_type_equals_data_type", where);
        List<JsonNode> payloads = new ArrayList<>();
        List<String> eventTypes = new ArrayList<>();
        for (SseFrame frame : frames) {
            String eventType = null;
            int eventLines = 0;
            int dataLines = 0;
            for (String line : frame.lines()) {
                if (line.startsWith("event:")) {
                    eventLines++;
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataLines++;
                } else {
                    throw new AssertionError(where + ": event+data framing violated by line '" + line + "'");
                }
            }
            assertEquals(1, eventLines, where + ": each frame must carry exactly one event: line");
            assertEquals(1, dataLines, where + ": each frame must carry exactly one data: line");
            JsonNode payload = readTree(frame.data(), where);
            assertTrue(payload.isObject(), where + ": each data payload must be a JSON object");
            if (eventTypeEqualsDataType) {
                assertEquals(eventType, payload.path("type").asString(),
                        where + ": event line type must equal data payload type");
            }
            payloads.add(payload);
            eventTypes.add(eventType);
        }

        // done_marker:false → NO [DONE] sentinel anywhere (message_stop is the end of a messages stream).
        assertFalse(pinBool(sse, "done_marker", where), where + ": only done_marker=false is implemented");
        assertFalse(pinBool(terminal, "done_marker", where), where + ": terminal.done_marker must agree");
        assertEquals(0, count(body, "[DONE]"), where + ": messages SSE must NOT carry a [DONE] sentinel");

        // Preamble: the first frames must be the pinned event types in order.
        JsonNode preamble = sse.path("preamble");
        assertTrue(preamble.isArray(), where + ": sse.preamble must be an array");
        for (int i = 0; i < preamble.size(); i++) {
            assertEquals(preamble.get(i).asString(), eventTypes.get(i),
                    where + ": preamble[" + i + "] event type");
        }

        // message_start usage: the SDK-required INITIAL placeholder, later overwritten by message_delta.
        JsonNode startUsagePins = sse.path("message_start_usage");
        requireKnownKeys(where + ".sse.message_start_usage", startUsagePins, MESSAGES_SSE_START_USAGE_KEYS);
        int startAt = eventTypes.indexOf("message_start");
        assertTrue(startAt >= 0, where + ": the stream must carry a message_start event");
        JsonNode startMessage = payloads.get(startAt).path("message");
        assertTrue(startMessage.isObject(), where + ": message_start must embed a message object");
        JsonNode startUsage = startMessage.path("usage");
        assertTrue(startUsage.isObject(), where + ": message_start.message.usage must be an object");
        assertMessagesCount(where + ".sse.message_start_usage", startUsage, startUsagePins, "input_tokens");
        assertMessagesCount(where + ".sse.message_start_usage", startUsage, startUsagePins, "output_tokens");

        // Block indices: content_block_start opens 0,1,2… contiguously; every delta/stop must refer to an
        // already-started block (a codec that restarts numbering or references a foreign index fails).
        assertEquals("contiguous_from_0", pinString(sse, "block_indices", where),
                where + ": only contiguous_from_0 block_indices is implemented");
        assertEquals("contiguous_from_0", pinString(terminal, "block_indices", where),
                where + ": terminal.block_indices must agree");
        int openedBlocks = 0;
        for (int i = 0; i < eventTypes.size(); i++) {
            String type = eventTypes.get(i);
            if (!type.startsWith("content_block_")) {
                continue;
            }
            JsonNode index = payloads.get(i).path("index");
            assertTrue(index.isIntegralNumber(),
                    where + ": frame " + i + " (" + type + ") must carry an integral index, got " + index);
            if ("content_block_start".equals(type)) {
                assertEquals(openedBlocks, index.intValue(),
                        where + ": content_block_start index must be contiguous from 0 at frame " + i);
                openedBlocks++;
            } else {
                assertTrue(index.intValue() >= 0 && index.intValue() < openedBlocks,
                        where + ": " + type + " at frame " + i + " must refer to an already-started block, got "
                                + index);
            }
        }
        assertTrue(openedBlocks > 0, where + ": the stream must open at least one content block");

        // Text block lifecycle: start(text) → text_delta(s) concatenating to delta_concat → stop.
        JsonNode textBlock = sse.path("text_block");
        requireKnownKeys(where + ".sse.text_block", textBlock, MESSAGES_SSE_TEXT_BLOCK_KEYS);
        String startType = pinString(textBlock, "start_type", where);
        String deltaType = pinString(textBlock, "delta_type", where);
        String deltaConcat = pinString(textBlock, "delta_concat", where);
        boolean closed = pinBool(textBlock, "closed", where);
        int textStartAt = -1;
        int textStopAt = -1;
        int textIndex = -1;
        StringBuilder textDeltas = new StringBuilder();
        for (int i = 0; i < eventTypes.size(); i++) {
            String type = eventTypes.get(i);
            JsonNode payload = payloads.get(i);
            if ("content_block_start".equals(type)
                    && startType.equals(payload.path("content_block").path("type").asString())) {
                assertTrue(textStartAt < 0, where + ": exactly one '" + startType + "' content_block_start");
                textStartAt = i;
                textIndex = payload.path("index").intValue();
            } else if ("content_block_delta".equals(type)
                    && deltaType.equals(payload.path("delta").path("type").asString())) {
                assertEquals(textIndex, payload.path("index").intValue(),
                        where + ": a " + deltaType + " must stay on the text block index");
                textDeltas.append(payload.path("delta").path("text").asString());
            } else if ("content_block_stop".equals(type) && payload.path("index").intValue() == textIndex
                    && textIndex >= 0) {
                assertTrue(textStopAt < 0, where + ": exactly one text content_block_stop");
                textStopAt = i;
            }
        }
        assertTrue(textStartAt >= 0, where + ": the stream must open a '" + startType + "' content block");
        assertEquals(deltaConcat, textDeltas.toString(),
                where + ": concatenated text deltas must equal text_block.delta_concat");
        assertEquals(closed, textStopAt > textStartAt,
                where + ": text_block.closed (startAt=" + textStartAt + ", stopAt=" + textStopAt + ")");

        // text.equals pin (independent of delta_concat: the fixture is the truth).
        JsonNode text = expected.path("text");
        requireKnownKeys(where + ".text", text, TEXT_KEYS);
        assertEquals(pinString(text, "equals", where), textDeltas.toString(), where + ": text.equals");

        // Thinking block lifecycle (s4): start(thinking) → thinking_delta(s) concatenating to
        // delta_concat → exactly one signature_delta (the runtime continuation envelope, llmgw1:) →
        // stop. Only asserted when the fixture pins it; s2 has no thinking, so neither pin is present.
        JsonNode thinkingBlock = sse.path("thinking_block");
        JsonNode thinkingPin = expected.path("thinking");
        if (!thinkingBlock.isMissingNode() || !thinkingPin.isMissingNode()) {
            assertFalse(thinkingBlock.isMissingNode(),
                    where + ": a thinking pin requires sse.thinking_block to define the deltas");
            assertFalse(thinkingPin.isMissingNode(),
                    where + ": sse.thinking_block requires the top-level thinking pin");
            requireKnownKeys(where + ".sse.thinking_block", thinkingBlock, MESSAGES_SSE_THINKING_BLOCK_KEYS);
            String thinkingStartType = pinString(thinkingBlock, "start_type", where);
            String thinkingDeltaType = pinString(thinkingBlock, "delta_type", where);
            String thinkingDeltaConcat = pinString(thinkingBlock, "delta_concat", where);
            String signaturePrefix = pinString(thinkingBlock, "signature_delta_prefix", where);
            boolean thinkingClosed = pinBool(thinkingBlock, "closed", where);
            int thinkingStartAt = -1;
            int thinkingStopAt = -1;
            int thinkingIndex = -1;
            int signatureCount = 0;
            StringBuilder thinkingDeltas = new StringBuilder();
            for (int i = 0; i < eventTypes.size(); i++) {
                String type = eventTypes.get(i);
                JsonNode payload = payloads.get(i);
                if ("content_block_start".equals(type)
                        && thinkingStartType.equals(payload.path("content_block").path("type").asString())) {
                    assertTrue(thinkingStartAt < 0,
                            where + ": exactly one '" + thinkingStartType + "' content_block_start");
                    thinkingStartAt = i;
                    thinkingIndex = payload.path("index").intValue();
                } else if ("content_block_delta".equals(type)
                        && thinkingDeltaType.equals(payload.path("delta").path("type").asString())) {
                    assertEquals(thinkingIndex, payload.path("index").intValue(),
                            where + ": a " + thinkingDeltaType + " must stay on the thinking block index");
                    thinkingDeltas.append(payload.path("delta").path("thinking").asString());
                } else if ("content_block_delta".equals(type)
                        && "signature_delta".equals(payload.path("delta").path("type").asString())) {
                    assertEquals(thinkingIndex, payload.path("index").intValue(),
                            where + ": a signature_delta must stay on the thinking block index");
                    signatureCount++;
                    JsonNode signature = payload.path("delta").path("signature");
                    assertTrue(signature.isString(),
                            where + ": signature_delta must carry a string signature, got " + signature);
                    assertTrue(signature.asString().startsWith(signaturePrefix),
                            where + ": signature_delta must carry the '" + signaturePrefix
                                    + "' namespace, got " + signature);
                } else if ("content_block_stop".equals(type)
                        && payload.path("index").intValue() == thinkingIndex && thinkingIndex >= 0) {
                    assertTrue(thinkingStopAt < 0, where + ": exactly one thinking content_block_stop");
                    thinkingStopAt = i;
                }
            }
            assertTrue(thinkingStartAt >= 0,
                    where + ": the stream must open a '" + thinkingStartType + "' content block");
            assertEquals(thinkingDeltaConcat, thinkingDeltas.toString(),
                    where + ": concatenated thinking deltas must equal thinking_block.delta_concat");
            assertEquals(1, signatureCount, where + ": exactly one signature_delta on the thinking block");
            assertEquals(thinkingClosed, thinkingStopAt > thinkingStartAt,
                    where + ": thinking_block.closed (startAt=" + thinkingStartAt + ", stopAt=" + thinkingStopAt + ")");
            // thinking.equals + before_text: the thinking block must precede the text block (index order).
            requireKnownKeys(where + ".thinking", thinkingPin, THINKING_KEYS);
            assertEquals(pinString(thinkingPin, "equals", where), thinkingDeltas.toString(),
                    where + ": thinking.equals");
            if (pinBool(thinkingPin, "before_text", where)) {
                assertTrue(thinkingIndex >= 0 && textIndex >= 0 && thinkingIndex < textIndex,
                        where + ": thinking.before_text (thinking block index " + thinkingIndex
                                + " must precede text block index " + textIndex + ")");
            }
        }

        // Terminal: one message_delta carrying stop_reason + the final usage, then message_stop LAST.
        JsonNode sseTerminal = sse.path("terminal");
        requireKnownKeys(where + ".sse.terminal", sseTerminal, MESSAGES_SSE_TERMINAL_SUB_KEYS);
        String stopReasonEvent = pinString(terminal, "stop_reason_event", where);
        assertEquals("message_delta", stopReasonEvent,
                where + ": only message_delta carries the messages stop_reason");
        int stopCount = pinInt(terminal, "message_stop_count", where);
        assertEquals(stopCount, pinInt(sseTerminal, "message_stop_count", where),
                where + ": sse.terminal.message_stop_count must agree");
        List<Integer> deltaIndices = new ArrayList<>();
        List<Integer> stopIndices = new ArrayList<>();
        for (int i = 0; i < eventTypes.size(); i++) {
            if (stopReasonEvent.equals(eventTypes.get(i))) {
                deltaIndices.add(i);
            } else if ("message_stop".equals(eventTypes.get(i))) {
                stopIndices.add(i);
            }
        }
        // The Anthropic terminal is one message_delta / message_stop pair per message.
        assertEquals(stopCount, deltaIndices.size(), where + ": exactly " + stopCount + " " + stopReasonEvent + " event(s)");
        assertEquals(stopCount, stopIndices.size(), where + ": exactly " + stopCount + " message_stop event(s)");
        int deltaAt = deltaIndices.get(deltaIndices.size() - 1);
        int stopAt = stopIndices.get(stopIndices.size() - 1);
        assertEquals(frames.size() - 1, stopAt, where + ": message_stop must be the LAST frame");
        assertTrue(deltaAt < stopAt, where + ": message_delta must precede message_stop");
        JsonNode deltaPayload = payloads.get(deltaAt);
        assertEquals(pinString(terminal, "stop_reason", where),
                deltaPayload.path("delta").path("stop_reason").asString(), where + ": terminal.stop_reason");
        assertEquals(pinString(sseTerminal, "message_delta_stop_reason", where),
                deltaPayload.path("delta").path("stop_reason").asString(),
                where + ": sse.terminal.message_delta_stop_reason must agree");

        // Usage lives at message_delta.usage and OVERWRITES the message_start placeholder (no double count).
        assertEquals("message_delta.usage", pinString(terminal, "usage_location", where),
                where + ": only message_delta.usage location is implemented");
        assertEquals("message_delta.usage", pinString(sseTerminal, "usage_location", where),
                where + ": sse.terminal.usage_location must agree");
        assertMessagesUsage(where, deltaPayload, deltaPayload.path("usage"), expected.path("usage"));
    }

    // ---------------------------------------------------------------- SSE pins (chat streaming)

    private static final Set<String> SSE_EXPECTED_KEYS =
            Set.of("transport", "terminal", "text", "usage", "sse", "thinking");
    private static final Set<String> SSE_TERMINAL_KEYS = Set.of(
            "object", "finish_reason", "done_literal", "done_count",
            "usage_chunk_after_terminal", "usage_chunk_choices_len");
    private static final Set<String> SSE_KEYS = Set.of(
            "framing", "first_delta_role", "content_delta_concat", "terminal_chunk", "usage_chunk", "done",
            "reasoning_delta_concat", "reasoning_before_content");
    private static final Set<String> SSE_DONE_KEYS = Set.of("literal", "count", "last");
    private static final Set<String> SSE_TERMINAL_CHUNK_KEYS = Set.of("finish_reason", "delta_empty");
    private static final Set<String> SSE_USAGE_CHUNK_KEYS = Set.of("after_terminal", "choices_len");

    /** One SSE frame: its raw lines (the framing truth) plus the joined data payload. */
    private record SseFrame(List<String> lines, String data) {
    }

    private static List<SseFrame> parseFrames(String body) {
        List<SseFrame> frames = new ArrayList<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            List<String> lines = List.of(block.split("\n"));
            StringBuilder data = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("data:")) {
                    String value = line.substring("data:".length());
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
            }
            frames.add(new SseFrame(lines, data.toString()));
        }
        return frames;
    }

    private static void assertSsePins(String where, JsonNode expected, String body) {
        requireKnownKeys(where, expected, SSE_EXPECTED_KEYS);
        List<SseFrame> frames = parseFrames(body);
        assertFalse(frames.isEmpty(), where + ": the stream must carry frames; body=" + body);

        JsonNode terminal = expected.path("terminal");
        requireKnownKeys(where + ".terminal", terminal, SSE_TERMINAL_KEYS);
        JsonNode sse = expected.path("sse");
        requireKnownKeys(where + ".sse", sse, SSE_KEYS);

        // Framing: chat SSE is data-only; a ':' heartbeat comment is not an event line.
        assertEquals("data-only", pinString(sse, "framing", where),
                where + ": only data-only framing is implemented");
        for (SseFrame frame : frames) {
            for (String line : frame.lines()) {
                assertTrue(line.startsWith("data:") || line.startsWith(":"),
                        where + ": data-only framing violated by line '" + line + "'");
            }
        }

        // The [DONE] sentinel: pinned literal, pinned count, pinned position.
        String doneLiteral = pinString(terminal, "done_literal", where);
        int doneCount = pinInt(terminal, "done_count", where);
        assertEquals(doneCount, count(body, doneLiteral), where + ": done_count for '" + doneLiteral + "'");
        JsonNode done = sse.path("done");
        requireKnownKeys(where + ".sse.done", done, SSE_DONE_KEYS);
        assertEquals(doneLiteral, pinString(done, "literal", where), where + ": done.literal must agree");
        assertEquals(doneCount, pinInt(done, "count", where), where + ": done.count must agree");
        if (pinBool(done, "last", where)) {
            assertEquals("[DONE]", frames.get(frames.size() - 1).data(),
                    where + ": [DONE] must be the LAST frame; body=" + body);
        }

        List<JsonNode> chunks = new ArrayList<>();
        for (SseFrame frame : frames) {
            if (!"[DONE]".equals(frame.data())) {
                chunks.add(readTree(frame.data(), where));
            }
        }
        assertFalse(chunks.isEmpty(), where + ": the stream must carry JSON chunks; body=" + body);
        String object = pinString(terminal, "object", where);
        for (JsonNode chunk : chunks) {
            assertEquals(object, chunk.path("object").asString(), where + ": every chunk object");
        }

        // Exactly ONE terminal chunk and exactly ONE usage-only chunk, in the pinned order.
        List<Integer> terminals = new ArrayList<>();
        List<Integer> usages = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode choices = chunks.get(i).path("choices");
            if (choices.isArray() && !choices.isEmpty() && choices.get(0).path("finish_reason").isString()) {
                terminals.add(i);
            }
            if (chunks.get(i).path("usage").isObject()) {
                usages.add(i);
            }
        }
        assertEquals(1, terminals.size(), where + ": exactly one entry terminal chunk; body=" + body);
        assertEquals(1, usages.size(), where + ": exactly one usage chunk; body=" + body);
        int terminalAt = terminals.get(0);
        int usageAt = usages.get(0);
        JsonNode terminalChunk = chunks.get(terminalAt);
        JsonNode usageChunk = chunks.get(usageAt);
        JsonNode terminalChoice = terminalChunk.path("choices").get(0);
        assertEquals(pinString(terminal, "finish_reason", where),
                terminalChoice.path("finish_reason").asString(), where + ": terminal finish_reason");
        assertEquals(pinBool(terminal, "usage_chunk_after_terminal", where), usageAt > terminalAt,
                where + ": usage_chunk_after_terminal");
        assertEquals(pinInt(terminal, "usage_chunk_choices_len", where),
                usageChunk.path("choices").size(), where + ": usage_chunk_choices_len");

        // Text + thinking: concatenate the content / reasoning_content deltas and record their order.
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        int firstTextAt = -1;
        int firstReasoningAt = -1;
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode choices = chunks.get(i).path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode content = delta.path("content");
            if (content.isString()) {
                text.append(content.asString());
                if (firstTextAt < 0) {
                    firstTextAt = i;
                }
            }
            JsonNode reasoningDelta = delta.path("reasoning_content");
            if (reasoningDelta.isString()) {
                reasoning.append(reasoningDelta.asString());
                if (firstReasoningAt < 0) {
                    firstReasoningAt = i;
                }
            }
        }
        assertEquals(pinString(expected.path("text"), "equals", where), text.toString(), where + ": text.equals");
        requireKnownKeys(where + ".text", expected.path("text"), TEXT_KEYS);
        JsonNode thinking = expected.path("thinking");
        if (!thinking.isMissingNode()) {
            assertThinkingOrder(where, thinking, reasoning.toString(), firstReasoningAt, firstTextAt);
        }
        JsonNode reasoningConcat = sse.path("reasoning_delta_concat");
        if (!reasoningConcat.isMissingNode()) {
            assertEquals(pinString(sse, "reasoning_delta_concat", where), reasoning.toString(),
                    where + ": reasoning_delta_concat");
        }
        JsonNode reasoningBefore = sse.path("reasoning_before_content");
        if (!reasoningBefore.isMissingNode()) {
            assertEquals(pinBool(sse, "reasoning_before_content", where),
                    firstReasoningAt >= 0 && firstTextAt >= 0 && firstReasoningAt < firstTextAt,
                    where + ": reasoning_before_content (reasoningAt=" + firstReasoningAt
                            + ", contentAt=" + firstTextAt + ")");
        }

        // Usage: exact pinned values from the usage-bearing chunk; absent paths are chunk-relative.
        assertUsage(where, usageChunk, usageChunk.path("usage"), expected.path("usage"));

        // SSE lifecycle sub-pins.
        assertEquals(pinString(sse, "first_delta_role", where),
                chunks.get(0).path("choices").get(0).path("delta").path("role").asString(),
                where + ": first_delta_role");
        assertEquals(pinString(sse, "content_delta_concat", where), text.toString(),
                where + ": content_delta_concat");
        JsonNode terminalPins = sse.path("terminal_chunk");
        requireKnownKeys(where + ".sse.terminal_chunk", terminalPins, SSE_TERMINAL_CHUNK_KEYS);
        assertEquals(pinString(terminalPins, "finish_reason", where),
                terminalChoice.path("finish_reason").asString(), where + ": terminal_chunk.finish_reason");
        JsonNode delta = terminalChoice.path("delta");
        assertTrue(delta.isObject(), where + ": the terminal chunk must carry a delta object");
        assertEquals(pinBool(terminalPins, "delta_empty", where), delta.isEmpty(),
                where + ": terminal_chunk.delta_empty");
        JsonNode usagePins = sse.path("usage_chunk");
        requireKnownKeys(where + ".sse.usage_chunk", usagePins, SSE_USAGE_CHUNK_KEYS);
        assertEquals(pinBool(usagePins, "after_terminal", where), usageAt > terminalAt,
                where + ": usage_chunk.after_terminal");
        assertEquals(pinInt(usagePins, "choices_len", where), usageChunk.path("choices").size(),
                where + ": usage_chunk.choices_len");
    }

    // ---------------------------------------------------------------- strict pin plumbing

    /**
     * thinking pins on a STREAM: the concatenated reasoning literal plus its frame order relative to
     * the answer text. Ordering is observable here because SSE has a frame sequence; a codec that
     * interleaves or postpones the reasoning fails the pinned order.
     */
    private static void assertThinkingOrder(String where, JsonNode pins, String reasoning,
                                            int reasoningAt, int textAt) {
        requireKnownKeys(where + ".thinking", pins, THINKING_KEYS);
        assertEquals(pinString(pins, "equals", where), reasoning, where + ": thinking.equals");
        assertEquals(pinBool(pins, "before_text", where),
                reasoningAt >= 0 && textAt >= 0 && reasoningAt < textAt,
                where + ": thinking.before_text (reasoningAt=" + reasoningAt + ", textAt=" + textAt + ")");
    }

    /** A fixture pin the helper does not understand must FAIL, never be skipped as a silent pass. */
    private static void requireKnownKeys(String where, JsonNode block, Set<String> supported) {
        assertTrue(block.isObject(), where + ": expected a pin object, got " + block);
        for (Map.Entry<String, JsonNode> field : block.properties()) {
            assertTrue(supported.contains(field.getKey()),
                    where + ": unimplemented fixture pin '" + field.getKey() + "' -- extend the pin helper");
        }
    }

    private static String pinString(JsonNode block, String field, String where) {
        JsonNode node = block.path(field);
        assertTrue(node.isString(), where + ": fixture pin '" + field + "' must be a string literal, got " + node);
        return node.asString();
    }

    private static int pinInt(JsonNode block, String field, String where) {
        JsonNode node = block.path(field);
        assertTrue(node.isIntegralNumber(), where + ": fixture pin '" + field + "' must be an integer, got " + node);
        return node.intValue();
    }

    private static boolean pinBool(JsonNode block, String field, String where) {
        JsonNode node = block.path(field);
        assertTrue(node.isBoolean(), where + ": fixture pin '" + field + "' must be a boolean, got " + node);
        return node.booleanValue();
    }

    private static long count(String haystack, String needle) {
        long n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static JsonNode readTree(String body, String where) {
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new AssertionError(where + ": entry response is not JSON: " + body, e);
        }
    }

    // ---------------------------------------------------------------- driving the real planes

    /** The mock's DeepSeek chat handler is registered at /mock/v1/chat/completions. */
    private static final String MOCK_BASE_PATH = "/mock/v1";
    private static final String UPSTREAM_MODEL = "deepseek-flash";

    private Result drive(Engine engine, JsonNode fixture, JsonNode step) throws Exception {
        JsonNode request = step.path("request");
        assertTrue(request.isObject(), "fixture step.request must be an object");
        JsonNode mock = fixture.path("mock");
        requireKnownKeys("fixture.mock", mock, MOCK_KEYS);
        GatewayProperties properties = startMock(pinInt(mock, "chunks", "fixture.mock"));
        PermitChain chain = new PermitChain();
        Route route = route(pinString(request, "model", "fixture.request"));
        var tasks = new InMemoryTaskRouteSticky(10, 60);
        String body = MAPPER.writeValueAsString(request);
        String entry = fixture.path("entry").asString();
        Map<String, String> inboundHeaders = inboundHeaders(step);
        try (var context = new ExtensionContext(); var upstream = new JdkHttpUpstream(properties)) {
            ConversionService conversion = realService(context, properties);
            RecordingUpstream recorder = new RecordingUpstream(upstream);
            return switch (engine) {
                case WEBFLUX -> runWebflux(body, entry, conversion, chain, router(route), recorder,
                        properties, allowAllHeaders(), tasks, inboundHeaders);
                case VT -> runVt(body, entry, conversion, chain, router(route), recorder,
                        properties, allowAllHeaders(), tasks, inboundHeaders);
            };
        }
    }

    /**
     * Optional per-step inbound headers (the degradation grant). Absent means the production default:
     * an EMPTY grant, so every positive scenario keeps driving exactly as before.
     */
    private static Map<String, String> inboundHeaders(JsonNode step) {
        JsonNode headers = step.path("headers");
        if (headers.isMissingNode() || headers.isNull()) {
            return Map.of();
        }
        assertTrue(headers.isObject(), "fixture step.headers must be a JSON object, got " + headers);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : headers.properties()) {
            assertTrue(entry.getValue().isString(),
                    "fixture step.headers values must be strings, got " + entry.getValue());
            out.put(entry.getKey(), entry.getValue().asString());
        }
        return out;
    }

    /**
     * Starts the REAL controlled mock on an OS-assigned loopback port (bind 0, read back the actual
     * port), so no probe-close-bind gap can let another listener win the port the gateway dials.
     */
    private GatewayProperties startMock(int chunks) throws IOException {
        // A multi-drive test starts one mock per drive; release the previous instance before binding
        // the next so no listening socket is orphaned (close + instance isolation). The prior drive's
        // Result already holds the fully-materialized body, so tearing its mock down here is safe.
        if (mock != null) {
            mock.destroy();
            mock = null;
        }
        GatewayProperties properties = new GatewayProperties();
        // The continuation envelope key is PROCESS-scoped in production (one ConversionService per
        // process, key generated once). Each drive builds a fresh context, and a blank configured key
        // makes ConversionModule generate a NEW SecureRandom key per drive -- which would break the
        // s4 cross-drive continuation (step1 mints with K1, step2 verifies with K2 != K1 -> HMAC fail).
        // Pin a STABLE key so both drives share one process scope, exactly as production does. This is
        // runtime config only: the envelope still genuinely verifies HMAC + projectId + reasoning SHA256.
        properties.getConversion().setContinuationKey(DRIVE_CONTINUATION_KEY);
        properties.setMaxConcurrentRequests(10);
        properties.getUpstream().setHeartbeatIntervalMs(0);
        properties.getMock().setEnabled(true);
        properties.getMock().setChunks(chunks);
        properties.getMock().setDelayMs(0);
        properties.getMock().setChunkIntervalMs(0);
        // Bind port 0 so the OS assigns a free ephemeral port ATOMICALLY. The old probe-close-bind
        // loop released the probe socket before the mock bound, so another listener could win that
        // port in the gap (HttpServer reuseaddr lets the bind still "succeed"); the gateway then
        // dialed a non-mock listener and got an intermittent upstream 401 in the full suite.
        properties.getMock().setPort(0);
        mock = new MockUpstreamServer(properties);
        port = mock.port();
        properties.getMock().setPort(port);
        return properties;
    }

    /**
     * JdkHttpUpstream.targetUri appends the codec-prepared protocol path to the route base URL
     * (unless the base already ends with it), so base {@code http://127.0.0.1:<port>/mock/v1} +
     * {@code /chat/completions} lands exactly on the mock's DeepSeek chat handler.
     */
    private Route route(String callerModel) {
        return new Route("contract", "*", callerModel, UPSTREAM_MODEL,
                "http://127.0.0.1:" + port + MOCK_BASE_PATH, "sk-test", "", 1, List.of(), "chat");
    }

    private static ConversionService realService(ExtensionContext context, GatewayProperties properties) {
        context.external(GatewayProperties.class, properties);
        context.loadModule("conversion");
        context.seal();
        return context.get(ConversionService.class);
    }

    /**
     * Transparent decorator over the REAL {@link JdkHttpUpstream}: it observes the outbound request
     * but changes nothing on the wire (still real loopback HTTP to the real mock). The mock branches
     * on a marker in the LAST user message, so a codec that drops/merges/reorders earlier turns leaves
     * the response byte-identical -- capturing the outbound body is the only way to catch that.
     */
    private static final class RecordingUpstream implements UpstreamProvider {
        private final UpstreamProvider delegate;
        private final List<UpstreamRequest> requests = new CopyOnWriteArrayList<>();
        /** Client cancels that reached the REAL upstream subscription (reactive release signal). */
        private final AtomicInteger cancels = new AtomicInteger();
        /** The blocking line loop returned/threw -- JdkHttpUpstream then closes the body (servlet signal). */
        private volatile boolean blockingExited;

        RecordingUpstream(UpstreamProvider delegate) {
            this.delegate = delegate;
        }

        public String id() {
            return delegate.id();
        }

        /**
         * Still fully transparent: every signal and every demand request is forwarded verbatim. The
         * ONLY addition is counting a downstream cancel before delegating it, so a client disconnect
         * that really reaches the transport is observable (a normal completion must leave it at 0).
         */
        public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            Flow.Publisher<String> real = delegate.streamRaw(request, listener);
            return subscriber -> real.subscribe(new Flow.Subscriber<String>() {
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        public void request(long n) {
                            subscription.request(n);
                        }

                        public void cancel() {
                            cancels.incrementAndGet();
                            subscription.cancel();
                        }
                    });
                }

                public void onNext(String line) {
                    subscriber.onNext(line);
                }

                public void onError(Throwable error) {
                    subscriber.onError(error);
                }

                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        }

        public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
            requests.add(request);
            try {
                delegate.streamRawBlocking(request, sink);
            } finally {
                // Reached on a clean EOF AND on a client-disconnect throw: either way the real
                // provider has left its try-with-resources and closed the upstream body.
                blockingExited = true;
            }
        }

        public String completeRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            return delegate.completeRaw(request, listener);
        }

        public byte[] fetchRaw(UpstreamRequest request) {
            requests.add(request);
            return delegate.fetchRaw(request);
        }

        public void verify() {
            delegate.verify();
        }

        String lastBody() {
            assertFalse(requests.isEmpty(), "the real upstream must have received a request");
            return requests.get(requests.size() - 1).rawJson();
        }

        /** NULL when the upstream was never called (pre-header capability rejection). */
        String lastBodyOrNull() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1).rawJson();
        }

        Map<String, String> lastPassHeaders() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1).passHeaders();
        }

        int cancels() {
            return cancels.get();
        }

        boolean blockingExited() {
            return blockingExited;
        }

        int requestCount() {
            return requests.size();
        }
    }

    /**
     * The outbound DeepSeek request must preserve the entry's multi-turn history and the route's
     * target model. Compared against the versioned fixture request (independent truth), never
     * recomputed from gateway code, so a turn-dropping transcoder regression fails here.
     *
     * <p>Content is compared by SHAPE, not by {@code asString()}: a null assistant content and an
     * omitted one are equivalent on the DeepSeek wire, while a dropped tool_call, a lost
     * tool_call_id pairing or a stringified null would all be silent corruption under a naive
     * string comparison.
     */
    private static void assertOutboundPreservesRequest(String where, JsonNode entryRequest, String upstreamBody) {
        assertTrue(upstreamBody != null && !upstreamBody.isBlank(),
                where + ": an outbound upstream request must have been captured");
        JsonNode outbound = readTree(upstreamBody, where);
        assertEquals(UPSTREAM_MODEL, outbound.path("model").asString(),
                where + ": outbound model must be the route target");
        JsonNode expectedMessages = entryRequest.path("messages");
        JsonNode actualMessages = outbound.path("messages");
        assertTrue(actualMessages.isArray(), where + ": outbound messages must be an array, got " + actualMessages);
        assertEquals(expectedMessages.size(), actualMessages.size(),
                where + ": multi-turn history length must be preserved");
        for (int i = 0; i < expectedMessages.size(); i++) {
            assertOutboundTurn(where + ".messages[" + i + "]", expectedMessages.get(i), actualMessages.get(i));
        }
        assertOutboundTools(where, entryRequest.path("tools"), outbound.path("tools"));
        assertOutboundToolChoice(where, entryRequest.path("tool_choice"), outbound.path("tool_choice"));
        assertOutboundThinking(where, entryRequest.path("reasoning_effort"), outbound);
    }

    /**
     * Responses entry outbound: the upstream is STILL DeepSeek Chat, so the captured body must carry
     * the multi-turn history rebuilt from {@code instructions} (prepended system) + {@code input} items
     * (message / function_call / function_call_output). Every literal comes from the versioned fixture
     * request (independent truth); outbound tools are compared against the FLAT responses fixture.
     */
    private static void assertOutboundPreservesResponsesRequest(String where, JsonNode entryRequest,
                                                                String upstreamBody) {
        assertTrue(upstreamBody != null && !upstreamBody.isBlank(),
                where + ": an outbound upstream request must have been captured");
        JsonNode outbound = readTree(upstreamBody, where);
        assertEquals(UPSTREAM_MODEL, outbound.path("model").asString(),
                where + ": outbound model must be the route target");
        List<JsonNode> expected = new ArrayList<>();
        JsonNode instructions = entryRequest.path("instructions");
        if (!instructions.isMissingNode() && !instructions.isNull()) {
            expected.add(MAPPER.createObjectNode().put("role", "system").put("content", instructions.asString()));
        }
        // Mirror decodeInput/flushTurn: reasoning + assistant-message + function_call items collapse
        // into ONE pending assistant turn (reasoning_content + content + tool_calls share one DeepSeek
        // message, matrix §4.3/§5.2 rows 207-208); a non-assistant message or function_call_output flushes
        // it. Unsupported item types FAIL (no silent skip). Truth is the fixture request. Current fixtures
        // carry one assistant message + one reasoning item per turn, so the last-wins put below equals the
        // codec's concatenation; a multi-item turn would need accumulation.
        ObjectNode pendingAssistant = null;
        for (JsonNode item : entryRequest.path("input")) {
            JsonNode typeNode = item.path("type");
            String type = typeNode.isMissingNode() || typeNode.isNull() ? "message" : typeNode.asString();
            switch (type) {
                case "message" -> {
                    String role = item.path("role").asString();
                    JsonNode content = item.path("content");
                    if ("assistant".equals(role)) {
                        // flushTurn: an assistant message merges into the pending assistant turn.
                        // Mirror DeepSeekChatCodec ASSISTANT (:128-134): joinText concatenates every text
                        // part into ONE string content; images are rejected upstream, so an assistant turn
                        // never carries one. A non-text part FAILS loudly (no silent skip / no dropped image).
                        String contentText = null;
                        if (content.isString()) {
                            contentText = content.asString();
                        } else if (content.isArray()) {
                            StringBuilder text = new StringBuilder();
                            for (JsonNode part : content) {
                                String partType = part.path("type").asString();
                                if ("output_text".equals(partType) || "input_text".equals(partType)) {
                                    text.append(part.path("text").asString());
                                } else {
                                    throw new AssertionError(where + ": assistant message only carries text parts, got '"
                                            + partType + "' -- extend the outbound helper");
                                }
                            }
                            contentText = text.toString();
                        }
                        if (pendingAssistant == null) {
                            pendingAssistant = MAPPER.createObjectNode();
                            pendingAssistant.put("role", "assistant");
                        }
                        if (contentText != null) {
                            pendingAssistant.put("content", contentText);
                        }
                    } else {
                        // Mirror DeepSeekChatCodec USER (:111-126): a string content stays a string; an array
                        // is rebuilt part-by-part into chat-style {type:text}/{type:image_url} parts
                        // (encodeUserPart :182-199), then flattened to a string ONLY when it is exactly one
                        // text part. Unknown part types FAIL loudly (no silent skip / no dropped image).
                        if (pendingAssistant != null) {
                            expected.add(pendingAssistant);
                            pendingAssistant = null;
                        }
                        var msg = MAPPER.createObjectNode();
                        msg.put("role", role);
                        if (content.isString()) {
                            msg.put("content", content.asString());
                        } else if (content.isArray()) {
                            ArrayNode parts = MAPPER.createArrayNode();
                            for (JsonNode part : content) {
                                String partType = part.path("type").asString();
                                switch (partType) {
                                    // output_text is assistant-only (ResponsesEntryCodec.decodeMessageContent
                                    // rejects it for user/system), so it falls through to the loud default.
                                    case "input_text" -> parts.addObject()
                                            .put("type", "text").put("text", part.path("text").asString());
                                    case "input_image" -> {
                                        // responses input_image: image_url is a STRING data URL, detail a
                                        // sibling key (decodeImage :1163-1228). The data URI round-trips
                                        // verbatim into the chat image_url.url (encodeUserPart :191-195).
                                        ObjectNode imageUrl = parts.addObject()
                                                .put("type", "image_url").putObject("image_url");
                                        imageUrl.put("url", part.path("image_url").asString());
                                        JsonNode detail = part.path("detail");
                                        if (!detail.isMissingNode() && !detail.isNull()) {
                                            imageUrl.put("detail", detail.asString());
                                        }
                                    }
                                    default -> throw new AssertionError(where
                                            + ": unsupported responses user content part type '" + partType
                                            + "' -- extend the outbound helper");
                                }
                            }
                            // USER flatten rule (encodeMessage :119-126): exactly one text part -> string.
                            if (parts.size() == 1 && "text".equals(parts.get(0).path("type").asString())) {
                                msg.put("content", parts.get(0).path("text").asString());
                            } else {
                                msg.set("content", parts);
                            }
                        }
                        expected.add(msg);
                    }
                }
                case "reasoning" -> {
                    // decodeReasoningItem + flushTurn: reasoning_text plaintext -> reasoning_content.
                    if (pendingAssistant == null) {
                        pendingAssistant = MAPPER.createObjectNode();
                        pendingAssistant.put("role", "assistant");
                    }
                    StringBuilder reasoning = new StringBuilder();
                    for (JsonNode part : item.path("content")) {
                        if ("reasoning_text".equals(part.path("type").asString())) {
                            reasoning.append(part.path("text").asString());
                        }
                    }
                    pendingAssistant.put("reasoning_content", reasoning.toString());
                }
                case "function_call" -> {
                    if (pendingAssistant == null) {
                        pendingAssistant = MAPPER.createObjectNode();
                        pendingAssistant.put("role", "assistant");
                    }
                    ArrayNode toolCalls = pendingAssistant.has("tool_calls")
                            ? (ArrayNode) pendingAssistant.get("tool_calls")
                            : pendingAssistant.putArray("tool_calls");
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", item.path("call_id").asString());
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", item.path("name").asString());
                    function.put("arguments", item.path("arguments").asString());
                }
                case "function_call_output" -> {
                    if (pendingAssistant != null) {
                        expected.add(pendingAssistant);
                        pendingAssistant = null;
                    }
                    var tool = MAPPER.createObjectNode();
                    tool.put("role", "tool");
                    tool.put("tool_call_id", item.path("call_id").asString());
                    // Mirror decodeToolOutput: a string, or input_text parts concatenated.
                    JsonNode output = item.path("output");
                    String content;
                    if (output.isString()) {
                        content = output.asString();
                    } else if (output.isArray()) {
                        StringBuilder text = new StringBuilder();
                        for (JsonNode part : output) {
                            if ("input_text".equals(part.path("type").asString())) {
                                text.append(part.path("text").asString());
                            }
                        }
                        content = text.toString();
                    } else {
                        throw new AssertionError(where + ": unsupported function_call_output shape: " + output);
                    }
                    tool.put("content", content);
                    expected.add(tool);
                }
                default -> throw new AssertionError(
                        where + ": unsupported responses input item type '" + type + "' -- extend the outbound helper");
            }
        }
        if (pendingAssistant != null) {
            expected.add(pendingAssistant);
        }
        JsonNode actualMessages = outbound.path("messages");
        assertTrue(actualMessages.isArray(), where + ": outbound messages must be an array, got " + actualMessages);
        assertEquals(expected.size(), actualMessages.size(),
                where + ": multi-turn history length must be preserved");
        for (int i = 0; i < expected.size(); i++) {
            assertOutboundTurn(where + ".messages[" + i + "]", expected.get(i), actualMessages.get(i));
        }
        JsonNode maxOutput = entryRequest.path("max_output_tokens");
        if (!maxOutput.isMissingNode() && !maxOutput.isNull()) {
            assertTrue(outbound.path("max_tokens").isIntegralNumber(),
                    where + ": max_output_tokens must reach the upstream as max_tokens (matrix §5.2)");
            assertEquals(maxOutput.intValue(), outbound.path("max_tokens").intValue(),
                    where + ": max_output_tokens must map to upstream max_tokens (matrix §5.2)");
        }
        assertOutboundResponsesTools(where, entryRequest.path("tools"), outbound.path("tools"));
        assertOutboundToolChoice(where, entryRequest.path("tool_choice"), outbound.path("tool_choice"));
        assertOutboundThinking(where, entryRequest.path("reasoning").path("effort"), outbound);
    }

    /**
     * Messages entry outbound: the upstream is STILL DeepSeek Chat regardless of the entry protocol, so
     * the captured body must carry the multi-turn history rebuilt from the top-level {@code system}
     * string (ONE leading system turn) plus the {@code messages} array, and {@code max_tokens} must reach
     * the upstream verbatim. Every literal comes from the versioned fixture request (independent truth).
     * The mock branches on a marker in the LAST user message, so a codec that drops/merges/reorders the
     * earlier turns leaves the response byte-identical -- only this outbound comparison catches it.
     *
     * <p>String content stays one turn; array content is split per the documented tool_result rule
     * (text / tool_use / tool_result / thinking / image are mapped, the user text+image parts staying a
     * chat-style content ARRAY). An entry knob needing its own outbound mapping still FAILS LOUDLY, so a
     * later slice must extend this helper explicitly instead of silently leaving it unchecked.
     */
    private static void assertOutboundPreservesMessagesRequest(String where, JsonNode entryRequest,
                                                               String upstreamBody) {
        assertTrue(upstreamBody != null && !upstreamBody.isBlank(),
                where + ": an outbound upstream request must have been captured");
        JsonNode outbound = readTree(upstreamBody, where);
        assertEquals(UPSTREAM_MODEL, outbound.path("model").asString(),
                where + ": outbound model must be the route target");
        List<JsonNode> expected = new ArrayList<>();
        JsonNode system = entryRequest.path("system");
        if (!system.isMissingNode() && !system.isNull()) {
            // decodeSystem (:777-780): a string system becomes one TextPart -> one leading system turn.
            assertTrue(system.isString(),
                    where + ": only a string system is implemented, got " + system
                            + " -- extend the outbound helper");
            expected.add(MAPPER.createObjectNode().put("role", "system").put("content", system.asString()));
        }
        JsonNode messages = entryRequest.path("messages");
        assertTrue(messages.isArray(),
                where + ": the messages entry request must carry a messages array, got " + messages);
        for (JsonNode message : messages) {
            String role = message.path("role").asString();
            JsonNode content = message.path("content");
            // decodeContent (:885-887): a string content stays ONE TextPart, which the DeepSeek chat codec
            // keeps a JSON string for every role (assistant included). An array content is split per the
            // documented tool_result rule (decodeMessage :848-867): each tool_result becomes an INDEPENDENT
            // tool message, the remaining blocks keep their relative order in user/assistant turns.
            if (content.isString()) {
                expected.add(MAPPER.createObjectNode().put("role", role).put("content", content.asString()));
            } else if (content.isArray()) {
                expected.addAll(messagesArrayContentTurns(where, role, content));
            } else {
                throw new AssertionError(where + ": unsupported content shape for role '" + role + "': " + content);
            }
        }
        JsonNode actualMessages = outbound.path("messages");
        assertTrue(actualMessages.isArray(), where + ": outbound messages must be an array, got " + actualMessages);
        assertEquals(expected.size(), actualMessages.size(),
                where + ": multi-turn history length must be preserved");
        for (int i = 0; i < expected.size(); i++) {
            assertOutboundTurn(where + ".messages[" + i + "]", expected.get(i), actualMessages.get(i));
        }
        JsonNode maxTokens = entryRequest.path("max_tokens");
        if (!maxTokens.isMissingNode() && !maxTokens.isNull()) {
            assertTrue(outbound.path("max_tokens").isIntegralNumber(),
                    where + ": max_tokens must reach the upstream as an integer, got " + outbound.path("max_tokens"));
            assertEquals(maxTokens.intValue(), outbound.path("max_tokens").intValue(),
                    where + ": max_tokens must map to upstream max_tokens");
        }
        // Conditional I2 guard (mirrors assertOutboundToolChoice/Thinking): when the entry DECLARES a
        // knob its mapped upstream form is asserted; when it declares none the outbound must NOT invent
        // one. s1/s2 declare none -> must-not-invent; s3 declares tools/tool_choice/thinking(disabled),
        // s4 declares thinking(adaptive) -> mapped.
        assertOutboundMessagesTools(where + ".tools", entryRequest.path("tools"), outbound.path("tools"));
        assertOutboundMessagesToolChoice(where + ".tool_choice",
                entryRequest.path("tool_choice"), outbound.path("tool_choice"));
        assertOutboundMessagesThinking(where + ".thinking", entryRequest.path("thinking"), outbound);
    }

    /**
     * Map ONE entry message's array content to the DeepSeek Chat upstream turns, replicating the
     * documented split (MessagesEntryCodec.decodeMessage :848-867): for a USER turn each tool_result
     * block becomes an INDEPENDENT {@code tool} message and the remaining text/image blocks keep their
     * relative order in {@code user} messages; for an ASSISTANT turn tool_use blocks become {@code tool_calls},
     * text blocks become the content string and thinking blocks become the reasoning_content plaintext
     * (the signature/continuation token is gateway-only and never reaches the upstream).
     * text/tool_use/tool_result/thinking/image are implemented; any other block FAILS LOUDLY so a later
     * slice extends explicitly. Every literal (id/name/input/content/media_type/data) comes from the
     * fixture request, never recomputed from gateway code. USER parts are flattened to a content STRING
     * only when exactly one text part (DeepSeek encodeMessage :119-126), so [text,image] (s5) stays an
     * ARRAY while [tool_result,text] (s3) still yields [tool message, user string]. An empty-string
     * assistant text block (joinText writes content:"") would need any-text semantics.
     */
    private static List<ObjectNode> messagesArrayContentTurns(String where, String role, JsonNode content) {
        List<ObjectNode> turns = new ArrayList<>();
        if ("assistant".equals(role)) {
            ObjectNode node = MAPPER.createObjectNode().put("role", "assistant");
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            ArrayNode toolCalls = null;
            for (JsonNode block : content) {
                String type = block.path("type").asString();
                switch (type) {
                    case "text" -> text.append(block.path("text").asString());
                    // A thinking block carries the reasoning PLAINTEXT to the upstream reasoning_content
                    // (DeepSeek encodeMessage ASSISTANT :139-158); the signature/continuation token is a
                    // gateway-only envelope and must NEVER reach the upstream, so it is not mapped here.
                    case "thinking" -> reasoning.append(block.path("thinking").asString());
                    case "tool_use" -> {
                        if (toolCalls == null) {
                            toolCalls = node.putArray("tool_calls");
                        }
                        ObjectNode call = toolCalls.addObject();
                        call.put("id", block.path("id").asString());
                        call.put("type", "function");
                        ObjectNode function = call.putObject("function");
                        function.put("name", block.path("name").asString());
                        JsonNode input = block.path("input");
                        assertTrue(input.isObject(),
                                where + ": tool_use.input must be a JSON object, got " + input);
                        function.put("arguments", MAPPER.writeValueAsString(input));
                    }
                    default -> throw new AssertionError(where + ": assistant block '" + type
                            + "' has no outbound mapping -- DeepSeek permanently rejects assistant images (rejectImages)");
                }
            }
            if (!text.isEmpty()) {
                node.put("content", text.toString());
            }
            if (!reasoning.isEmpty()) {
                node.put("reasoning_content", reasoning.toString());
            }
            turns.add(node);
            return turns;
        }
        if ("user".equals(role)) {
            // Mirror DeepSeekChatCodec USER (:111-126) + encodeUserPart (:182-199): contiguous text/image
            // blocks are rebuilt part-by-part into chat-style {type:text}/{type:image_url} parts, then
            // flattened to a STRING only when exactly one text part. A tool_result block still splits into
            // an INDEPENDENT tool message (decodeMessage :848-867); the pending parts are flushed FIRST so
            // the relative order survives (s3 continuation [tool_result,text] -> [tool message, user string]).
            ArrayNode pending = MAPPER.createArrayNode();
            for (JsonNode block : content) {
                String type = block.path("type").asString();
                switch (type) {
                    case "text" -> pending.addObject()
                            .put("type", "text").put("text", block.path("text").asString());
                    case "image" -> {
                        // Messages image blocks carry source:{type:base64,media_type,data} and NO detail key,
                        // so ImagePart.detail is always null (decodeImage :951 -> inlineImage :974 -> :1010)
                        // and the upstream image_url must NOT gain one. The data URI is rebuilt from the
                        // FIXTURE literals (independent truth), never from gateway code.
                        JsonNode source = block.path("source");
                        assertEquals("base64", source.path("type").asString(),
                                where + ": only an inline base64 image source is implemented, got " + source);
                        ObjectNode imageUrl = pending.addObject()
                                .put("type", "image_url").putObject("image_url");
                        imageUrl.put("url", "data:" + source.path("media_type").asString()
                                + ";base64," + source.path("data").asString());
                    }
                    case "tool_result" -> {
                        pending = flushMessagesUserParts(turns, pending);
                        JsonNode result = block.path("content");
                        assertTrue(result.isString(),
                                where + ": only string tool_result content is implemented, got " + result);
                        turns.add(MAPPER.createObjectNode().put("role", "tool")
                                .put("tool_call_id", block.path("tool_use_id").asString())
                                .put("content", result.asString()));
                    }
                    default -> throw new AssertionError(where + ": user block '" + type
                            + "' has no outbound mapping -- DeepSeek user content only carries text/image parts");
                }
            }
            flushMessagesUserParts(turns, pending);
            assertFalse(turns.isEmpty(), where + ": a user array content must produce at least one turn");
            return turns;
        }
        throw new AssertionError(where + ": array content for role '" + role + "' is not implemented -- extend the helper");
    }

    /**
     * Emit the accumulated chat-style user parts as ONE user turn and hand back a FRESH empty array (the
     * flushed array is owned by the emitted node, so it must not be cleared in place). USER flatten rule
     * (DeepSeek encodeMessage :119-126): exactly one text part -> content STRING, otherwise content ARRAY.
     * An empty accumulation emits nothing, keeping the tool_result-only case a bare tool message.
     */
    private static ArrayNode flushMessagesUserParts(List<ObjectNode> turns, ArrayNode pending) {
        if (!pending.isEmpty()) {
            ObjectNode node = MAPPER.createObjectNode().put("role", "user");
            if (pending.size() == 1 && "text".equals(pending.get(0).path("type").asString())) {
                node.put("content", pending.get(0).path("text").asString());
            } else {
                node.set("content", pending);
            }
            turns.add(node);
        }
        return MAPPER.createArrayNode();
    }

    /**
     * Messages entry outbound tools: the fixture tools are Anthropic-shaped ({name,description,
     * input_schema}) while the DeepSeek Chat upstream tools are OpenAI function-wrapped
     * ({type:function,function:{name,description,parameters}}). The codec round-trips input_schema through
     * a JSON-Schema string, so the comparison covers type/name/description/parameters (deep), reading the
     * FLAT Anthropic fields (NOT the chat/responses OpenAI wrapper). A turn that declares no tools must
     * not gain any (must-not-invent).
     */
    private static void assertOutboundMessagesTools(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tools must not be invented when the entry declares none, got " + actual);
            return;
        }
        assertTrue(expected.isArray(), where + ": fixture tools must be an array, got " + expected);
        assertTrue(actual.isArray(), where + ": outbound tools must be an array, got " + actual);
        assertEquals(expected.size(), actual.size(), where + ": tool definition count preserved");
        for (int i = 0; i < expected.size(); i++) {
            String at = where + ".tools[" + i + "]";
            JsonNode want = expected.get(i);
            JsonNode got = actual.get(i);
            assertEquals("function", got.path("type").asString(), at + ": tool type preserved");
            assertEquals(want.path("name").asString(), got.path("function").path("name").asString(),
                    at + ": function.name preserved");
            JsonNode wantDescription = want.path("description");
            if (!wantDescription.isMissingNode()) {
                assertEquals(wantDescription.asString(), got.path("function").path("description").asString(),
                        at + ": function.description preserved");
            }
            JsonNode wantSchema = want.path("input_schema");
            assertTrue(wantSchema.isObject(), at + ": fixture input_schema must be an object, got " + wantSchema);
            assertEquals(wantSchema, got.path("function").path("parameters"),
                    at + ": input_schema must map to function.parameters (deep)");
        }
    }

    /**
     * Messages tool_choice: the entry object form {type:auto} maps to the upstream STRING "auto"
     * (decodeToolChoice :1195 + DeepSeek encodeToolChoice :275). Only the auto passthrough is implemented
     * this slice; any other type FAILS LOUDLY so a later slice extends explicitly. A turn that declares no
     * tool_choice must not gain one (must-not-invent).
     */
    private static void assertOutboundMessagesToolChoice(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tool_choice must not be invented when the entry declares none, got " + actual);
            return;
        }
        assertTrue(expected.isObject(), where + ": fixture tool_choice must be an object, got " + expected);
        String type = expected.path("type").asString();
        String upstream = switch (type) {
            case "auto" -> "auto";
            default -> throw new AssertionError(where + ": tool_choice.type '" + type
                    + "' needs an explicit outbound mapping -- extend the helper");
        };
        assertTrue(actual.isString(), where + ": outbound tool_choice must stay a string, got " + actual);
        assertEquals(upstream, actual.asString(), where + ": tool_choice preserved");
    }

    /**
     * Messages thinking: the entry {type:disabled} maps to the upstream thinking:{type:disabled} with NO
     * reasoning_effort (decodeThinking :670 sets thinkingEnabled=false and no derivedEffort; DeepSeek
     * encodeRequest :67-72); {type:adaptive} maps to thinking:{type:enabled} + reasoning_effort:"high"
     * (decodeThinking :691-695 derives effort high). disabled/adaptive are implemented; enabled/between_tools
     * FAIL LOUDLY so a later slice extends explicitly. An entry that declares no thinking must produce neither
     * thinking nor reasoning_effort (must-not-invent).
     */
    private static void assertOutboundMessagesThinking(String where, JsonNode expected, JsonNode outbound) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(outbound.path("reasoning_effort").isMissingNode(),
                    where + ": reasoning_effort must not be invented when the entry declares no thinking, got "
                            + outbound.path("reasoning_effort"));
            assertTrue(outbound.path("thinking").isMissingNode(),
                    where + ": thinking must not be invented when the entry declares no thinking, got "
                            + outbound.path("thinking"));
            return;
        }
        assertTrue(expected.isObject(), where + ": fixture thinking must be an object, got " + expected);
        String type = expected.path("type").asString();
        switch (type) {
            case "disabled" -> {
                assertEquals("disabled", outbound.path("thinking").path("type").asString(),
                        where + ": thinking.type must map to the upstream disabled switch");
                assertTrue(outbound.path("reasoning_effort").isMissingNode(),
                        where + ": disabled thinking must not carry reasoning_effort, got "
                                + outbound.path("reasoning_effort"));
            }
            case "adaptive" -> {
                // matrix §5.3 E: adaptive enables thinking with a FIXED derived effort high
                // (decodeThinking :691-695 -> DeepSeek encodeRequest :67-72, upstreamEffort high->high).
                assertEquals("enabled", outbound.path("thinking").path("type").asString(),
                        where + ": adaptive thinking must map to the upstream enabled switch");
                assertEquals("high", outbound.path("reasoning_effort").asString(),
                        where + ": adaptive thinking must derive reasoning_effort=high (matrix §5.3 E)");
            }
            default -> throw new AssertionError(where + ": thinking.type '" + type
                    + "' needs an explicit outbound mapping -- extend the helper (enabled/between_tools are later slices)");
        }
    }

    /**
     * The outbound tool definitions must reach the upstream intact. The codec legitimately drops the
     * entry-only {@code strict} flag and round-trips {@code parameters} through a JSON-Schema string,
     * so the comparison covers type/name/description/parameters (deep), never {@code strict}. A turn
     * that declares no tools must not gain any (must-not-invent).
     */
    private static void assertOutboundTools(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tools must not be invented when the entry declares none, got " + actual);
            return;
        }
        assertTrue(expected.isArray(), where + ": fixture tools must be an array, got " + expected);
        assertTrue(actual.isArray(), where + ": outbound tools must be an array, got " + actual);
        assertEquals(expected.size(), actual.size(), where + ": tool definition count preserved");
        for (int i = 0; i < expected.size(); i++) {
            String at = where + ".tools[" + i + "]";
            JsonNode want = expected.get(i).path("function");
            JsonNode got = actual.get(i);
            assertEquals("function", got.path("type").asString(), at + ": tool type preserved");
            assertEquals(want.path("name").asString(), got.path("function").path("name").asString(),
                    at + ": function.name preserved");
            JsonNode wantDescription = want.path("description");
            if (!wantDescription.isMissingNode()) {
                assertEquals(wantDescription.asString(), got.path("function").path("description").asString(),
                        at + ": function.description preserved");
            }
            JsonNode wantParameters = want.path("parameters");
            if (!wantParameters.isMissingNode()) {
                assertEquals(wantParameters, got.path("function").path("parameters"),
                        at + ": function.parameters JSON Schema preserved");
            }
        }
    }

    /**
     * Responses entry outbound tools: the fixture tools are FLAT ({type,name,description,parameters,
     * strict}) while the DeepSeek Chat upstream tools are NESTED ({type,function:{name,...}}). The codec
     * legitimately drops the entry-only {@code strict} flag and round-trips {@code parameters} through a
     * JSON-Schema string, so the comparison covers type/name/description/parameters (deep), never strict.
     * Mirrors the chat {@link #assertOutboundTools} but reads the FLAT fixture fields; a turn that
     * declares no tools must not gain any (must-not-invent).
     */
    private static void assertOutboundResponsesTools(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tools must not be invented when the entry declares none, got " + actual);
            return;
        }
        assertTrue(expected.isArray(), where + ": fixture tools must be an array, got " + expected);
        assertTrue(actual.isArray(), where + ": outbound tools must be an array, got " + actual);
        assertEquals(expected.size(), actual.size(), where + ": tool definition count preserved");
        for (int i = 0; i < expected.size(); i++) {
            String at = where + ".tools[" + i + "]";
            JsonNode want = expected.get(i);
            JsonNode got = actual.get(i);
            assertEquals("function", got.path("type").asString(), at + ": tool type preserved");
            assertFalse(got.path("function").has("strict"), at + ": entry-only strict must not reach upstream");
            assertEquals(want.path("name").asString(), got.path("function").path("name").asString(),
                    at + ": function.name preserved");
            JsonNode wantDescription = want.path("description");
            if (!wantDescription.isMissingNode()) {
                assertEquals(wantDescription.asString(), got.path("function").path("description").asString(),
                        at + ": function.description preserved");
            }
            JsonNode wantParameters = want.path("parameters");
            if (!wantParameters.isMissingNode()) {
                assertEquals(wantParameters, got.path("function").path("parameters"),
                        at + ": function.parameters JSON Schema preserved");
            }
        }
    }

    /**
     * tool_choice passthrough (none/auto/required) must survive verbatim; a turn that declares none
     * must not gain one. The fixtures use the string form; the named-object form is a later slice.
     */
    private static void assertOutboundToolChoice(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode() || expected.isNull()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tool_choice must not be invented when the entry declares none, got " + actual);
            return;
        }
        assertTrue(expected.isString(),
                where + ": fixture tool_choice must be the passthrough string form, got " + expected);
        assertTrue(actual.isString(), where + ": outbound tool_choice must stay a string, got " + actual);
        assertEquals(expected.asString(), actual.asString(), where + ": tool_choice preserved");
    }

    /**
     * The entry reasoning_effort maps to the upstream effort and the thinking switch per matrix §4.2
     * (independent spec truth, not recomputed from the codec): none-&gt;none/disabled,
     * minimal|low-&gt;low, medium|high|xhigh-&gt;high, ultra|max-&gt;max; any non-none effort enables
     * thinking. An entry that declares no effort must produce neither field (must-not-invent).
     */
    private static void assertOutboundThinking(String where, JsonNode effort, JsonNode outbound) {
        if (effort.isMissingNode() || effort.isNull()) {
            assertTrue(outbound.path("reasoning_effort").isMissingNode(),
                    where + ": reasoning_effort must not be invented when the entry declares none, got "
                            + outbound.path("reasoning_effort"));
            assertTrue(outbound.path("thinking").isMissingNode(),
                    where + ": thinking must not be invented when the entry declares no effort, got "
                            + outbound.path("thinking"));
            return;
        }
        String entryEffort = effort.asString();
        String upstreamEffort = switch (entryEffort) {
            case "none" -> "none";
            case "minimal", "low" -> "low";
            case "medium", "high", "xhigh" -> "high";
            case "ultra", "max" -> "max";
            default -> throw new AssertionError(where + ": unsupported fixture reasoning_effort " + entryEffort);
        };
        assertEquals(upstreamEffort, outbound.path("reasoning_effort").asString(),
                where + ": reasoning_effort must map to the upstream effort (matrix §4.2)");
        String expectedThinking = "none".equals(entryEffort) ? "disabled" : "enabled";
        assertEquals(expectedThinking, outbound.path("thinking").path("type").asString(),
                where + ": thinking.type must reflect whether the entry effort is none");
    }

    /** One outbound turn: role, content shape, tool_calls, tool_call_id pairing and carried reasoning. */
    private static void assertOutboundTurn(String where, JsonNode expected, JsonNode actual) {
        assertEquals(expected.path("role").asString(), actual.path("role").asString(),
                where + ": role preserved");
        JsonNode content = expected.path("content");
        JsonNode actualContent = actual.path("content");
        if (content.isString()) {
            assertTrue(actualContent.isString(),
                    where + ": text content must stay a JSON string, got " + actualContent);
            assertEquals(content.asString(), actualContent.asString(), where + ": content preserved");
        } else if (content.isNull() || content.isMissingNode()) {
            assertTrue(actualContent.isNull() || actualContent.isMissingNode(),
                    where + ": null content must not become text, got " + actualContent);
        } else if (content.isArray()) {
            assertTrue(actualContent.isArray(),
                    where + ": multi-part content must stay an array, got " + actualContent);
            assertEquals(content.size(), actualContent.size(), where + ": content part count preserved");
            for (int j = 0; j < content.size(); j++) {
                assertOutboundContentPart(where + ".content[" + j + "]", content.get(j), actualContent.get(j));
            }
        } else {
            throw new AssertionError(where + ": unsupported fixture content shape: " + content);
        }
        assertOutboundToolCalls(where, expected.path("tool_calls"), actual.path("tool_calls"));
        JsonNode toolCallId = expected.path("tool_call_id");
        if (!toolCallId.isMissingNode()) {
            assertTrue(actual.path("tool_call_id").isString(),
                    where + ": tool_call_id must survive the round trip, got " + actual.path("tool_call_id"));
            assertEquals(toolCallId.asString(), actual.path("tool_call_id").asString(),
                    where + ": tool_call_id pairing preserved");
        }
        JsonNode reasoning = expected.path("reasoning_content");
        if (!reasoning.isMissingNode()) {
            JsonNode actualReasoning = actual.path("reasoning_content");
            assertTrue(actualReasoning.isString(),
                    where + ": carried reasoning must stay native plaintext, got " + actualReasoning);
            assertEquals(reasoning.asString(), actualReasoning.asString(),
                    where + ": reasoning_content preserved");
        } else {
            assertTrue(actual.path("reasoning_content").isMissingNode(),
                    where + ": reasoning_content must not be invented for a turn that carries none, got "
                            + actual.path("reasoning_content"));
        }
    }

    /** Request-side tool_calls carry arguments as a JSON STRING on both sides; compare them semantically. */
    private static void assertOutboundToolCalls(String where, JsonNode expected, JsonNode actual) {
        if (expected.isMissingNode()) {
            assertTrue(actual.isMissingNode() || actual.isNull(),
                    where + ": tool_calls must not be invented for a turn that has none, got " + actual);
            return;
        }
        assertTrue(expected.isArray(), where + ": fixture tool_calls must be an array, got " + expected);
        assertTrue(actual.isArray(), where + ": outbound tool_calls must be an array, got " + actual);
        assertEquals(expected.size(), actual.size(), where + ": tool_calls count preserved");
        for (int i = 0; i < expected.size(); i++) {
            String at = where + ".tool_calls[" + i + "]";
            JsonNode want = expected.get(i);
            JsonNode got = actual.get(i);
            assertEquals(want.path("id").asString(), got.path("id").asString(), at + ": id preserved");
            assertEquals(want.path("type").asString(), got.path("type").asString(), at + ": type preserved");
            assertEquals(want.path("function").path("name").asString(),
                    got.path("function").path("name").asString(), at + ": function.name preserved");
            JsonNode gotArguments = got.path("function").path("arguments");
            assertTrue(gotArguments.isString(),
                    at + ": outbound function.arguments must stay a JSON string, got " + gotArguments);
            assertEquals(readTree(want.path("function").path("arguments").asString(), at),
                    readTree(gotArguments.asString(), at), at + ": function.arguments preserved");
        }
    }

    private static void assertOutboundContentPart(String where, JsonNode expected, JsonNode actual) {
        String type = expected.path("type").asString();
        assertEquals(type, actual.path("type").asString(), where + ": content part type preserved");
        if ("text".equals(type)) {
            assertEquals(expected.path("text").asString(), actual.path("text").asString(),
                    where + ": text part preserved");
            return;
        }
        if ("image_url".equals(type)) {
            InlineImage want = decodeDataUri(expected.path("image_url").path("url").asString(), where);
            InlineImage got = decodeDataUri(actual.path("image_url").path("url").asString(), where);
            assertEquals(want.mediaType(), got.mediaType(), where + ": image media_type preserved");
            assertEquals(sha256Hex(want.bytes()), sha256Hex(got.bytes()),
                    where + ": image bytes must reach the upstream intact (sha256)");
            JsonNode detail = expected.path("image_url").path("detail");
            if (!detail.isMissingNode()) {
                assertEquals(detail.asString(), actual.path("image_url").path("detail").asString(),
                        where + ": image detail preserved");
            } else {
                assertTrue(actual.path("image_url").path("detail").isMissingNode(),
                        where + ": image detail must not be invented when the entry declares none");
            }
            return;
        }
        throw new AssertionError(where + ": unsupported fixture content part type '" + type + "'");
    }

    /**
     * The image pin is observed on the CAPTURED OUTBOUND request: "accepted" means the inline image
     * survived the entry decode and the upstream encode and reached the controlled upstream intact.
     * The digest is computed with the JDK only (MessageDigest + Base64) from the decoded data URI, so
     * a gateway that re-encodes, truncates or substitutes the image cannot agree with the pinned hash.
     */
    private static void assertImageAccepted(String where, JsonNode pins, String upstreamBody) {
        requireKnownKeys(where + ".image", pins, IMAGE_KEYS);
        boolean accepted = pinBool(pins, "accepted", where);
        List<InlineImage> images = outboundImages(where, upstreamBody);
        assertEquals(accepted, !images.isEmpty(),
                where + ": image.accepted must match an inline image actually reaching the upstream");
        if (!accepted) {
            return;
        }
        assertEquals(1, images.size(), where + ": exactly one inline image must be sent, got " + images.size());
        InlineImage image = images.get(0);
        assertEquals(pinString(pins, "media_type", where), image.mediaType(), where + ": image.media_type");
        assertEquals(pinString(pins, "sha256", where), sha256Hex(image.bytes()),
                where + ": image.sha256 of the outbound image bytes");
    }

    /** Every inline image the gateway actually put on the wire, in message/part order. */
    private static List<InlineImage> outboundImages(String where, String upstreamBody) {
        assertTrue(upstreamBody != null && !upstreamBody.isBlank(),
                where + ": an outbound upstream request must have been captured");
        JsonNode outbound = readTree(upstreamBody, where);
        List<InlineImage> images = new ArrayList<>();
        for (JsonNode message : outbound.path("messages")) {
            JsonNode content = message.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("image_url".equals(part.path("type").asString())) {
                    images.add(decodeDataUri(part.path("image_url").path("url").asString(), where));
                }
            }
        }
        return images;
    }

    private record InlineImage(String mediaType, byte[] bytes) {
    }

    /** {@code data:<media_type>;base64,<payload>} -- the only inline form stage 1 accepts. */
    private static InlineImage decodeDataUri(String url, String where) {
        assertTrue(url.startsWith("data:"), where + ": an inline image must be a data URI, got " + url);
        int comma = url.indexOf(',');
        assertTrue(comma > "data:".length(), where + ": malformed data URI: " + url);
        String header = url.substring("data:".length(), comma);
        assertTrue(header.endsWith(";base64"), where + ": an inline image must be base64, got " + header);
        String mediaType = header.substring(0, header.length() - ";base64".length());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(url.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new AssertionError(where + ": inline image base64 must decode", e);
        }
        return new InlineImage(mediaType, bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JDK", e);
        }
    }

    private static Router router(Route route) {
        return new Router() {
            public Route resolve(String model) {
                return route;
            }

            public Route resolve(ProtocolKind kind, String model) {
                return route;
            }

            public Route byId(String id) {
                return route;
            }

            public List<Route> fallbacks(Route primary) {
                return List.of();
            }

            public List<Route> all() {
                return List.of(route);
            }
        };
    }

    private static HeaderPolicy allowAllHeaders() {
        return new HeaderPolicy() {
            public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) {
                Map<String, String> out = new LinkedHashMap<>(values);
                // RFC 7230 §3.2: the harness preserves wire case, so strip ignoring case (a lowercase
                // remove() would be a silent no-op). UpstreamRequest also strips these defensively.
                out.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                        || name.equalsIgnoreCase("content-type")
                        || name.equalsIgnoreCase("content-length")
                        || name.equalsIgnoreCase("host"));
                return out;
            }

            public List<String> toClient(Map<String, List<String>> values) {
                return List.of();
            }
        };
    }

    private static Result runWebflux(String body, String entry, ConversionService conversion, PermitChain chain,
                                     Router router, RecordingUpstream upstream, GatewayProperties properties,
                                     HeaderPolicy headers, InMemoryTaskRouteSticky tasks,
                                     Map<String, String> inboundHeaders) {
        var controller = new WebfluxGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        String path = switch (entry) {
            case "chat" -> "/v1/chat/completions";
            case "responses" -> "/v1/responses";
            case "messages" -> "/v1/messages";
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        };
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-token");
        for (Map.Entry<String, String> header : inboundHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        MockServerHttpRequest request = builder.body(body);
        MockServerHttpResponse response = new MockServerHttpResponse();
        switch (entry) {
            case "chat" -> controller.handleChat(request, response).block(Duration.ofSeconds(20));
            case "responses" -> controller.handleResponses(request, response).block(Duration.ofSeconds(20));
            case "messages" -> controller.handleMessages(request, response).block(Duration.ofSeconds(20));
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        }
        String responseBody = response.getBodyAsString().block(Duration.ofSeconds(20));
        return new Result(response.getStatusCode().value(), responseBody,
                List.copyOf(chain.finished), chain.active.get(), upstream.lastBodyOrNull(), upstream.cancels(),
                upstream.lastPassHeaders(), upstream.requestCount());
    }

    /**
     * Drives the REAL servlet controller with Proxy-based request/response objects: the response
     * bytes are captured exactly as a servlet container would write them (no Spring context).
     */
    private static Result runVt(String body, String entry, ConversionService conversion, PermitChain chain,
                                Router router, RecordingUpstream upstream, GatewayProperties properties,
                                HeaderPolicy headers, InMemoryTaskRouteSticky tasks,
                                Map<String, String> inboundHeaders) throws Exception {
        int[] status = {0};
        boolean[] committed = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var servletOut = new ServletOutputStream() {
            public void write(int b) {
                committed[0] = true;
                out.write(b);
            }

            public boolean isReady() {
                return true;
            }

            public void setWriteListener(WriteListener listener) {
            }
        };
        HttpServletRequest request = vtRequest(body, inboundHeaders);
        HttpServletResponse response = vtResponse(status, committed, servletOut);
        var controller = new VirtualThreadGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        // The servlet Proxy carries NO path: the VT plane dispatches purely on which handler is called.
        switch (entry) {
            case "chat" -> controller.handleChat(request, response);
            case "responses" -> controller.handleResponses(request, response);
            case "messages" -> controller.handleMessages(request, response);
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        }
        return new Result(status[0], out.toString(StandardCharsets.UTF_8),
                List.copyOf(chain.finished), chain.active.get(), upstream.lastBodyOrNull(), upstream.cancels(),
                upstream.lastPassHeaders(), upstream.requestCount());
    }

    /** The servlet request side, shared by the success and the cancel probe (identical bytes). */
    private static HttpServletRequest vtRequest(String body) {
        return vtRequest(body, Map.of());
    }

    private static HttpServletRequest vtRequest(String body, Map<String, String> extraHeaders) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headerMap = new LinkedHashMap<>();
        headerMap.put("Authorization", "Bearer test-token");
        headerMap.put("Content-Type", "application/json");
        headerMap.putAll(extraHeaders);

        var servletIn = new ServletInputStream() {
            final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

            public int read() {
                return in.read();
            }

            public boolean isFinished() {
                return in.available() == 0;
            }

            public boolean isReady() {
                return true;
            }

            public void setReadListener(ReadListener listener) {
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                ConversionContractTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> servletIn;
                    case "getHeader" -> headerMap.get((String) args[0]);
                    case "getHeaderNames" -> Collections.enumeration(headerMap.keySet());
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** The servlet response side, shared by the success and the cancel probe (identical semantics). */
    private static HttpServletResponse vtResponse(int[] status, boolean[] committed, ServletOutputStream out) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ConversionContractTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setStatus" -> {
                        status[0] = (Integer) args[0];
                        yield null;
                    }
                    case "getStatus" -> status[0];
                    case "getOutputStream" -> out;
                    case "isCommitted" -> committed[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * Servlet client-disconnect probe (same technique as the engine's StreamDisconnectTest): the
     * output stream captures bytes until the first CONTENT frame is complete, then every later write
     * throws IOException("client closed"). The real controller must turn that into a FAILED finish,
     * release the permit and leave the upstream body closed -- never a fabricated success terminal.
     *
     * <p>The controller runs on its own virtual thread (as in production) behind a short latch, so a
     * regression that fails to disconnect surfaces as a fast failure instead of a 30s stall hang.
     */
    private static CancelResult runVtCancel(String body, String entry, ConversionService conversion,
                                            PermitChain chain, Router router, RecordingUpstream upstream,
                                            GatewayProperties properties, HeaderPolicy headers,
                                            InMemoryTaskRouteSticky tasks) throws Exception {
        int[] status = {0};
        boolean[] committed = {false};
        boolean[] armed = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var servletOut = new ServletOutputStream() {
            public void write(int b) throws IOException {
                if (armed[0]) {
                    throw new IOException("client closed");
                }
                committed[0] = true;
                out.write(b);
                // Only inspect at a line boundary: a frame is complete at "\n\n".
                if (b == '\n' && firstContentFrameSeen(out.toString(StandardCharsets.UTF_8), entry)) {
                    armed[0] = true;
                }
            }

            /**
             * The disconnect must fire on the FIRST frame's flush, not on a later write: the fixture
             * silences the upstream for 30s after that frame, so a "throw on the next write" probe
             * would never get another write and would hang instead of cancelling. A real container
             * fails the flush once the client is gone, which is exactly what StreamSink.write does
             * right after out.write(...).
             */
            public void flush() throws IOException {
                if (armed[0]) {
                    throw new IOException("client closed");
                }
            }

            public boolean isReady() {
                return true;
            }

            public void setWriteListener(WriteListener listener) {
            }
        };
        HttpServletRequest request = vtRequest(body);
        HttpServletResponse response = vtResponse(status, committed, servletOut);
        var controller = new VirtualThreadGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread probe = Thread.ofVirtual().name("vt-cancel-probe").start(() -> {
            try {
                // vtRequest carries no path: the servlet plane dispatches purely on which handler is called.
                switch (entry) {
                    case "chat" -> controller.handleChat(request, response);
                    case "responses" -> controller.handleResponses(request, response);
                    case "messages" -> controller.handleMessages(request, response);
                    default -> throw new AssertionError("unknown entry '" + entry + "'");
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                settled.countDown();
            }
        });
        try {
            assertTrue(settled.await(10, TimeUnit.SECONDS),
                    () -> "the disconnected stream must settle promptly, not hang on the upstream stall"
                            + " (armed=" + armed[0] + ", captured=" + out.toString(StandardCharsets.UTF_8) + ")");
            assertNull(failure.get(), "a client disconnect must not escape the servlet controller");
        } finally {
            // Interrupt so a regression's blocked read aborts before driveCancel's HttpClient.close()
            // awaits the stalled exchange (~30s); keeps the failure fast as the javadoc above promises.
            probe.interrupt();
            probe.join(5000);
        }
        String capturedBody = out.toString(StandardCharsets.UTF_8);
        return new CancelResult(status[0], capturedBody,
                List.copyOf(chain.finished), chain.active.get(), upstream.lastBody(),
                firstContentFrameSeen(capturedBody, entry), upstream.cancels(), upstream.blockingExited(),
                upstream.requestCount());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    // ---------------------------------------------------------------- slice c: chat s6 cancel -> release -> recover

    /**
     * A CANCEL drive: the same real entry codec, real DeepSeek transcoder, real mock and real loopback
     * HTTP as {@link #drive}, but the client stops reading right after the first CONTENT frame instead
     * of draining to a terminal. The blocking runners must NOT be reused here: they wait for a
     * completion that the fixture's 30s mid-stream stall never delivers inside the test budget.
     */
    private CancelResult driveCancel(Engine engine, JsonNode fixture, JsonNode step) throws Exception {
        JsonNode request = step.path("request");
        assertTrue(request.isObject(), "fixture step.request must be an object");
        JsonNode mock = fixture.path("mock");
        requireKnownKeys("fixture.mock", mock, MOCK_KEYS);
        GatewayProperties properties = startMock(pinInt(mock, "chunks", "fixture.mock"));
        PermitChain chain = new PermitChain();
        Route route = route(pinString(request, "model", "fixture.request"));
        var tasks = new InMemoryTaskRouteSticky(10, 60);
        String body = MAPPER.writeValueAsString(request);
        String entry = fixture.path("entry").asString();
        try (var context = new ExtensionContext(); var upstream = new JdkHttpUpstream(properties)) {
            ConversionService conversion = realService(context, properties);
            RecordingUpstream recorder = new RecordingUpstream(upstream);
            return switch (engine) {
                case WEBFLUX -> runWebfluxCancel(body, entry, conversion, chain, router(route), recorder,
                        properties, allowAllHeaders(), tasks);
                case VT -> runVtCancel(body, entry, conversion, chain, router(route), recorder,
                        properties, allowAllHeaders(), tasks);
            };
        }
    }

    /**
     * Reactive client-disconnect probe. A {@link ServerHttpResponseDecorator} taps the body the
     * controller writes and stops consuming once the first CONTENT frame has been captured; the
     * resulting cancel travels back through the real pipeline into the real upstream Flow
     * subscription. The bytes are captured in the tap (NOT via {@code getBodyAsString().block()},
     * which a cancelled stream may never complete) and a short latch bounds the wait so a regression
     * that fails to cancel surfaces as a fast failure instead of a 30s hang.
     */
    private static CancelResult runWebfluxCancel(String body, String entry, ConversionService conversion,
                                                 PermitChain chain, Router router, RecordingUpstream upstream,
                                                 GatewayProperties properties, HeaderPolicy headers,
                                                 InMemoryTaskRouteSticky tasks) throws Exception {
        var controller = new WebfluxGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        String path = switch (entry) {
            case "chat" -> "/v1/chat/completions";
            case "responses" -> "/v1/responses";
            case "messages" -> "/v1/messages";
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        };
        MockServerHttpRequest request = MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-token")
                .body(body);
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        StringBuffer captured = new StringBuffer();
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerHttpResponse response = new ServerHttpResponseDecorator(delegate) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // toString(Charset) is non-destructive, so the tap observes the exact bytes the
                // client received without consuming them. takeUntil is INCLUSIVE: the frame that
                // completes the first content delta is delivered, then the source is cancelled.
                Flux<DataBuffer> tapped = Flux.<DataBuffer>from(body)
                        .doOnNext(buffer -> captured.append(buffer.toString(StandardCharsets.UTF_8)))
                        .takeUntil(buffer -> firstContentFrameSeen(captured.toString(), entry));
                return super.writeWith(tapped);
            }
        };
        Mono<Void> handling = switch (entry) {
            case "chat" -> controller.handleChat(request, response);
            case "responses" -> controller.handleResponses(request, response);
            case "messages" -> controller.handleMessages(request, response);
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        };
        handling.subscribe(
                ignored -> { },
                error -> {
                    failure.set(error);
                    settled.countDown();
                },
                settled::countDown);
        assertTrue(settled.await(10, TimeUnit.SECONDS),
                "the cancelled stream must settle promptly, not hang on the upstream stall");
        assertNull(failure.get(), "the cancelled stream must not surface an error to the client");
        String capturedBody = captured.toString();
        return new CancelResult(delegate.getStatusCode().value(), capturedBody,
                List.copyOf(chain.finished), chain.active.get(), upstream.lastBody(),
                firstContentFrameSeen(capturedBody, entry), upstream.cancels(), upstream.blockingExited(),
                upstream.requestCount());
    }

    /** Only COMPLETE SSE frames: a tap can stop mid-frame, and a partial payload is not a pin. */
    private static List<SseFrame> completeFrames(String body) {
        String normalized = body.replace("\r\n", "\n");
        int last = normalized.lastIndexOf("\n\n");
        return last < 0 ? List.of() : parseFrames(normalized.substring(0, last + 2));
    }

    /**
     * The cancel trigger: a delivered frame carrying a textual content increment -- chat
     * choices[0].delta.content, responses response.output_text.delta, or messages
     * content_block_delta/text_delta. The responses preamble (response.created/in_progress) and
     * item-open frames carry no text and must NOT arm the probe; likewise the messages preamble
     * (message_start) and content_block_start carry no increment. An unknown entry FAILS LOUDLY instead
     * of silently applying the chat shape.
     */
    private static boolean firstContentFrameSeen(String body, String entry) {
        for (SseFrame frame : completeFrames(body)) {
            if (frame.data().isBlank() || "[DONE]".equals(frame.data())) {
                continue;
            }
            JsonNode data = readTree(frame.data(), "cancel-probe");
            switch (entry) {
                case "chat" -> {
                    JsonNode choices = data.path("choices");
                    if (choices.isArray() && !choices.isEmpty()
                            && choices.get(0).path("delta").path("content").isString()) {
                        return true;
                    }
                }
                case "responses" -> {
                    if ("response.output_text.delta".equals(data.path("type").asString())
                            && data.path("delta").isString()) {
                        return true;
                    }
                }
                case "messages" -> {
                    if ("content_block_delta".equals(data.path("type").asString())
                            && "text_delta".equals(data.path("delta").path("type").asString())
                            && data.path("delta").path("text").isString()) {
                        return true;
                    }
                }
                default -> throw new AssertionError("unknown entry '" + entry + "'");
            }
        }
        return false;
    }

    /** The content increment of the first COMPLETE content frame, or "" if none (cancel-step truth). */
    private static String firstContentDelta(String body, String entry) {
        for (SseFrame frame : completeFrames(body)) {
            if (frame.data().isBlank() || "[DONE]".equals(frame.data())) {
                continue;
            }
            JsonNode data = readTree(frame.data(), "cancel-probe");
            switch (entry) {
                case "chat" -> {
                    JsonNode content = data.path("choices").path(0).path("delta").path("content");
                    if (content.isString()) {
                        return content.asString();
                    }
                }
                case "responses" -> {
                    if ("response.output_text.delta".equals(data.path("type").asString())
                            && data.path("delta").isString()) {
                        return data.path("delta").asString();
                    }
                }
                case "messages" -> {
                    if ("content_block_delta".equals(data.path("type").asString())
                            && "text_delta".equals(data.path("delta").path("type").asString())
                            && data.path("delta").path("text").isString()) {
                        return data.path("delta").path("text").asString();
                    }
                }
                default -> throw new AssertionError("unknown entry '" + entry + "'");
            }
        }
        return "";
    }

    /**
     * A SUCCESS terminal: chat's finish_reason-bearing choice or [DONE] sentinel; responses'
     * response.completed event; messages' message_stop event. The [DONE] check runs for EVERY entry, so
     * a fabricated sentinel is caught on responses and messages too -- their normal streams simply
     * never emit one. An unknown entry FAILS LOUDLY instead of silently applying the chat shape.
     */
    private static boolean successTerminalSeen(String body, String entry) {
        for (SseFrame frame : completeFrames(body)) {
            if ("[DONE]".equals(frame.data())) {
                return true;
            }
            if (frame.data().isBlank()) {
                continue;
            }
            JsonNode data = readTree(frame.data(), "cancel-probe");
            switch (entry) {
                case "chat" -> {
                    JsonNode choices = data.path("choices");
                    if (choices.isArray() && !choices.isEmpty()
                            && choices.get(0).path("finish_reason").isString()) {
                        return true;
                    }
                }
                case "responses" -> {
                    if ("response.completed".equals(data.path("type").asString())) {
                        return true;
                    }
                }
                case "messages" -> {
                    if ("message_stop".equals(data.path("type").asString())) {
                        return true;
                    }
                }
                default -> throw new AssertionError("unknown entry '" + entry + "'");
            }
        }
        return false;
    }

    /**
     * Cancel pins are NOT the success pins: the body is truncated by design, so reusing
     * {@link #assertExpected} would assert a terminal that must never arrive. Every literal comes
     * from the fixture (independent truth), and an unimplemented pin key still FAILS.
     */
    private static final Set<String> CANCEL_EXPECTED_KEYS = Set.of("transport", "cancel");
    private static final Set<String> CANCEL_KEYS = Set.of("trigger", "stall_marker", "assertions");
    private static final Set<String> CANCEL_ASSERTION_KEYS = Set.of(
            "first_frame_received", "active_permits_after_cancel",
            "no_success_terminal_after_cancel", "upstream_connection_released");

    /** Status + accounting are entry-independent; the frame pins are entry-specific. */
    private static void assertCancelPins(String where, JsonNode expected, CancelResult result, Engine engine,
                                         String recoveryText) {
        assertCancelPins(where, expected, result, engine, recoveryText, "chat");
    }

    private static void assertCancelPins(String where, JsonNode expected, CancelResult result, Engine engine,
                                         String recoveryText, String entry) {
        requireKnownKeys(where, expected, CANCEL_EXPECTED_KEYS);
        assertEquals("sse", pinString(expected, "transport", where),
                where + ": the cancel probe drives the streaming transport");
        JsonNode cancel = expected.path("cancel");
        requireKnownKeys(where + ".cancel", cancel, CANCEL_KEYS);
        assertEquals("after_first_content_frame", pinString(cancel, "trigger", where),
                where + ": only the after-first-content-frame trigger is implemented");
        String stallMarker = pinString(cancel, "stall_marker", where);
        // The stall must really have been asked of the controlled upstream: without it the probe
        // would race a normal fast stream and prove nothing about mid-stream cancellation.
        assertTrue(result.upstreamBody().contains(stallMarker),
                where + ": the outbound request must carry the pinned stall marker '" + stallMarker + "'");
        JsonNode pins = cancel.path("assertions");
        requireKnownKeys(where + ".cancel.assertions", pins, CANCEL_ASSERTION_KEYS);

        assertEquals(pinBool(pins, "first_frame_received", where), result.firstFrameReceived(),
                where + ": first_frame_received; body=" + result.body());
        // first_frame_received shares the probe's arming predicate, so it is only structural. The CONTENT
        // of the cancelled first frame is pinned independently: the mock is deterministic, so that delta
        // must be a non-empty prefix of the recovery step's fixture text -- a corrupted first frame fails.
        String firstDelta = firstContentDelta(result.body(), entry);
        assertFalse(firstDelta.isEmpty(), where + ": the cancel probe must capture a real first content delta");
        assertTrue(recoveryText.startsWith(firstDelta),
                where + ": the cancelled first delta '" + firstDelta + "' must prefix the pinned recovery text '"
                        + recoveryText + "'");
        assertEquals(pinInt(pins, "active_permits_after_cancel", where), result.activePermits(),
                where + ": active_permits_after_cancel (the reservation must be released on cancel)");
        assertEquals(List.of(false), result.finished(),
                where + ": exactly one FAILED finish -- a cancel is never billed as a success");
        assertEquals(200, result.status(),
                where + ": headers are committed (200) before the first content frame the cancel follows");
        // A failed CLIENT write says nothing about upstream health: retrying or splicing a fallback
        // into a stream the client already left wastes work and corrupts the protocol view.
        assertEquals(1, result.upstreamRequests(),
                where + ": a client disconnect must not retry or fall back to another upstream");
        boolean noSuccessTerminal = pinBool(pins, "no_success_terminal_after_cancel", where);
        assertEquals(noSuccessTerminal, !successTerminalSeen(result.body(), entry),
                where + ": no_success_terminal_after_cancel; body=" + result.body());
        if (noSuccessTerminal) {
            // Unconditional: the responses entry NEVER emits [DONE], so 0 is its invariant too.
            assertEquals(0, count(result.body(), "data: [DONE]"),
                    where + ": the [DONE] sentinel must not be fabricated after a cancel");
        }
        if (pinBool(pins, "upstream_connection_released", where)) {
            // Engine-specific observation of the SAME contract: the reactive plane must propagate
            // the cancel into the real upstream subscription (which aborts the HTTP exchange), the
            // servlet plane must leave the blocking line loop so the real body gets closed.
            switch (engine) {
                case WEBFLUX -> assertTrue(result.upstreamCancels() >= 1,
                        where + ": the client cancel must reach the real upstream subscription (cancels="
                                + result.upstreamCancels() + ")");
                case VT -> assertTrue(result.blockingExited(),
                        where + ": the blocking upstream line loop must exit so the body is closed");
            }
        }
    }

    // ---------------------------------------------------------------- slice 1 scenarios

    @Test
    void chatS1NonStreamMultiTurnMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/chat-s1", initial.path("expected"), result);
        assertOutboundPreservesRequest("webflux/chat-s1", initial.path("request"), result.upstreamBody());
    }

    @Test
    void chatS1NonStreamMultiTurnMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/chat-s1", initial.path("expected"), result);
        assertOutboundPreservesRequest("vt/chat-s1", initial.path("request"), result.upstreamBody());
    }

    @Test
    void chatS2StreamTextAndUsageMatchTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/chat-s2", initial.path("expected"), result);
        assertOutboundPreservesRequest("webflux/chat-s2", initial.path("request"), result.upstreamBody());
    }

    @Test
    void chatS2StreamTextAndUsageMatchTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/chat-s2", initial.path("expected"), result);
        assertOutboundPreservesRequest("vt/chat-s2", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d1: responses s1 non-stream multi-turn

    @Test
    void responsesS1NonStreamMultiTurnMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/responses-s1", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s1", initial.path("request"), result.upstreamBody());
    }

    @Test
    void responsesS1NonStreamMultiTurnMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/responses-s1", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s1", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d1: responses s2 stream text + usage

    @Test
    void responsesS2StreamTextAndUsageMatchTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/responses-s2", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s2", initial.path("request"), result.upstreamBody());
    }

    @Test
    void responsesS2StreamTextAndUsageMatchTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/responses-s2", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s2", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d2: responses s3 tool call -> client executes -> continuation

    /**
     * responses s3: each step is an INDEPENDENT controlled-upstream request (no server-side conversation
     * storage), so both steps are driven separately. The entry codec re-encodes the DeepSeek tool_call as
     * a responses function_call output item; the continuation rebuilds the history with function_call +
     * function_call_output input items, which the codec maps back to assistant tool_calls + a tool message
     * on the DeepSeek Chat upstream (matrix §5.2 rows 207-208). The captured upstream body must STILL be
     * DeepSeek Chat shape while the client receives the responses entry protocol.
     */
    @Test
    void responsesS3ToolCallContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/responses-s3-initial", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s3-initial",
                initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/responses-s3-continuation", continuation.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void responsesS3ToolCallContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/responses-s3-initial", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s3-initial",
                initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/responses-s3-continuation", continuation.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d2: responses s4 thinking + carry-state continuation

    /**
     * responses s4: step1 streams thinking then the answer; step2 carries the reasoning state back.
     * Each step is an INDEPENDENT controlled-upstream request (no server-side conversation storage).
     * The entry codec re-encodes the DeepSeek reasoning_content as a responses reasoning output item
     * and mints a continuation envelope (encrypted_content) bound to the runtime project + reasoning
     * text. The continuation rebuilds the history with a reasoning input item + an assistant message,
     * which the codec maps back to ONE assistant message carrying reasoning_content + content on the
     * DeepSeek Chat upstream (matrix §4.3/§5.2). The captured upstream body must STILL be DeepSeek Chat
     * shape while the client receives the responses entry protocol.
     *
     * <p>The fixture's pre-minted envelope binds projectId="fixture-project", but the runtime uses the
     * degenerate identity mapping (tenantId=projectId=keyId="sk-test"), so the continuation substitutes
     * the RUNTIME-minted token captured from step1 (the fixture notes explicitly permit this: verify()
     * binds projectId + reasoning text SHA256, not the runtime item id).
     */
    @Test
    void responsesS4ThinkingContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/responses-s4-initial", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s4-initial",
                initial.path("request"), result.upstreamBody());
        String token = captureRuntimeReasoningToken("webflux/responses-s4-initial", result.body());
        JsonNode continuation = substituteReasoningToken(step(fixture, "continuation"), token);
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/responses-s4-continuation", continuation.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void responsesS4ThinkingContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/responses-s4-initial", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s4-initial",
                initial.path("request"), result.upstreamBody());
        String token = captureRuntimeReasoningToken("vt/responses-s4-initial", result.body());
        JsonNode continuation = substituteReasoningToken(step(fixture, "continuation"), token);
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/responses-s4-continuation", continuation.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    /**
     * Capture the runtime-minted continuation envelope from step1's SSE terminal: the reasoning output
     * item's encrypted_content (closeReasoning snapshots the enveloped item into response.output[]).
     * The token must be non-empty and carry the llmgw1: namespace, otherwise the substitution is void.
     */
    private static String captureRuntimeReasoningToken(String where, String sseBody) {
        List<SseFrame> frames = parseFrames(sseBody);
        assertFalse(frames.isEmpty(), where + ": the stream must carry frames to capture the token");
        JsonNode output = null;
        for (SseFrame frame : frames) {
            JsonNode payload = readTree(frame.data(), where);
            if ("response.completed".equals(payload.path("type").asString())) {
                output = payload.path("response").path("output");
            }
        }
        assertTrue(output != null && output.isArray(),
                where + ": a response.completed terminal with output[] must exist, got " + output);
        String token = null;
        for (JsonNode item : output) {
            if ("reasoning".equals(item.path("type").asString())) {
                JsonNode encrypted = item.path("encrypted_content");
                assertTrue(encrypted.isString(),
                        where + ": the reasoning item must carry encrypted_content, got " + encrypted);
                token = encrypted.asString();
                break;
            }
        }
        assertTrue(token != null && !token.isEmpty(),
                where + ": a runtime reasoning envelope must have been minted");
        assertTrue(token.startsWith("llmgw1:"),
                where + ": the captured token must carry the llmgw1: namespace, got " + token);
        return token;
    }

    /**
     * Deep-copy the continuation step and substitute the runtime-minted envelope into its reasoning
     * item, keeping the reasoning_text plaintext unchanged (verify binds the text SHA256). The returned
     * step's request is what gets driven AND what the outbound fidelity assertion compares against.
     */
    private static JsonNode substituteReasoningToken(JsonNode step, String token) {
        ObjectNode copy = (ObjectNode) step.deepCopy();
        JsonNode input = copy.path("request").path("input");
        assertTrue(input.isArray(), "continuation request.input must be an array, got " + input);
        boolean replaced = false;
        for (JsonNode item : input) {
            if ("reasoning".equals(item.path("type").asString())) {
                ObjectNode reasoningItem = (ObjectNode) item;
                String originalToken = reasoningItem.path("encrypted_content").asString();
                JsonNode originalContent = reasoningItem.path("content").deepCopy();
                reasoningItem.put("encrypted_content", token);
                // verify() binds the reasoning_text SHA256: the plaintext content must survive untouched.
                assertEquals(originalContent, reasoningItem.path("content"),
                        "substitution must not alter the reasoning content plaintext");
                assertNotEquals(originalToken, token,
                        "the runtime token must differ from the fixture's pre-minted wrong-project token");
                assertEquals(token, reasoningItem.path("encrypted_content").asString(),
                        "the reasoning item must carry the runtime-minted token");
                replaced = true;
            }
        }
        assertTrue(replaced, "the continuation must carry a reasoning item to substitute the token into");
        return copy;
    }

    // ---------------------------------------------------------------- slice d5: responses s5 inline image understanding

    /**
     * responses s5: a user turn carrying input_text + an inline input_image data URL. The entry codec
     * decodes the responses image (image_url is a STRING data URL, detail a sibling key) into an ImagePart;
     * the DeepSeek Chat upstream encode rebuilds it as a chat-style {type:image_url,image_url:{url,detail}}
     * part. Because the user turn has TWO parts, the upstream content stays an ARRAY (matrix §5.2) while
     * the client receives the responses entry protocol. The image.accepted pin is observed on the captured
     * outbound body against the independent fixture media_type + sha256, never recomputed from gateway code.
     */
    @Test
    void responsesS5ImageUnderstandingMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/responses-s5", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s5", initial.path("request"), result.upstreamBody());
    }

    @Test
    void responsesS5ImageUnderstandingMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/responses-s5", initial.path("expected"), result, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s5", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d6: responses s6 cancel -> release -> recover

    /**
     * responses s6: cancel mid-stream -> release -> recover, on the REAL reactive plane. The cancel step
     * stops reading right after the first responses content frame (response.output_text.delta) while the
     * controlled upstream stalls for 30s; its own active_permits_after_cancel==0 pin proves the reservation
     * was released. The recovery step then drives a NORMAL request on a FRESH chain/mock in the SAME test,
     * proving the disconnect left the route healthy -- it does not re-prove the cancel step's permit count,
     * which that step already pins.
     */
    @Test
    void responsesS6CancelReleaseRecoverMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("responses", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "responses-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.WEBFLUX, fixture, cancel);
        assertCancelPins("webflux/responses-s6-cancel", cancel.path("expected"), cancelled, Engine.WEBFLUX,
                recoveryText, "responses");
        Result next = drive(Engine.WEBFLUX, fixture, recovery);
        assertExpected("webflux/responses-s6-recovery", recovery.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("webflux/responses-s6-recovery", recovery.path("request"), next.upstreamBody());
        // Non-vacuity guard for the cancel signal above: the observer must count ONLY real client
        // cancels. A normal completion that also "cancelled" would make upstream_connection_released
        // pass for the wrong reason.
        assertEquals(0, next.upstreamCancels(),
                "webflux/responses-s6-recovery: a normal completion must NOT cancel the upstream transport");
    }

    /** responses s6 on the REAL servlet plane: the client disconnect is a failed flush, as a container does. */
    @Test
    void responsesS6CancelReleaseRecoverMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("responses", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "responses-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.VT, fixture, cancel);
        assertCancelPins("vt/responses-s6-cancel", cancel.path("expected"), cancelled, Engine.VT,
                recoveryText, "responses");
        Result next = drive(Engine.VT, fixture, recovery);
        assertExpected("vt/responses-s6-recovery", recovery.path("expected"), next, "responses");
        assertOutboundPreservesResponsesRequest("vt/responses-s6-recovery", recovery.path("request"), next.upstreamBody());
        // No upstreamCancels guard here: the servlet plane is blocking (streamRawBlocking), so that
        // reactive counter is structurally 0 and asserting it would pin a constant. The cancel step's
        // upstream_connection_released is non-vacuous instead because the pinned 30s stall + the 10s
        // settle latch + finished==[false] force blockingExited to mean "left the loop on disconnect",
        // never a clean EOF.
    }

    // ---------------------------------------------------------------- slice b: chat s3 tool call -> client executes -> continuation

    /**
     * Each step is an INDEPENDENT controlled-upstream request: the client rebuilds the continuation
     * with the full history (no server-side conversation storage), so both steps are driven separately.
     */
    @Test
    void chatS3ToolCallContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/chat-s3-initial", initial.path("expected"), result);
        assertOutboundPreservesRequest("webflux/chat-s3-initial", initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/chat-s3-continuation", continuation.path("expected"), next);
        assertOutboundPreservesRequest("webflux/chat-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void chatS3ToolCallContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/chat-s3-initial", initial.path("expected"), result);
        assertOutboundPreservesRequest("vt/chat-s3-initial", initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/chat-s3-continuation", continuation.path("expected"), next);
        assertOutboundPreservesRequest("vt/chat-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    // ---------------------------------------------------------------- slice b: chat s4 thinking + carry-state continuation

    @Test
    void chatS4ThinkingContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/chat-s4-initial", initial.path("expected"), result);
        assertOutboundPreservesRequest("webflux/chat-s4-initial", initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/chat-s4-continuation", continuation.path("expected"), next);
        assertOutboundPreservesRequest("webflux/chat-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void chatS4ThinkingContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/chat-s4-initial", initial.path("expected"), result);
        assertOutboundPreservesRequest("vt/chat-s4-initial", initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/chat-s4-continuation", continuation.path("expected"), next);
        assertOutboundPreservesRequest("vt/chat-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    // ---------------------------------------------------------------- slice b: chat s5 inline image understanding

    @Test
    void chatS5ImageUnderstandingMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/chat-s5", initial.path("expected"), result);
        assertOutboundPreservesRequest("webflux/chat-s5", initial.path("request"), result.upstreamBody());
    }

    @Test
    void chatS5ImageUnderstandingMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/chat-s5", initial.path("expected"), result);
        assertOutboundPreservesRequest("vt/chat-s5", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice c: chat s6 cancel -> release -> recover

    /**
     * chat s6: cancel mid-stream -> release -> recover, on the REAL reactive plane. The cancel step
     * stops reading right after the first content frame while the controlled upstream stalls for 30s;
     * its own active_permits_after_cancel==0 pin proves the reservation was released. The recovery step
     * then drives a NORMAL request on a FRESH chain/mock in the SAME test, proving the disconnect left
     * the route healthy (a poisoned route or a stuck upstream would fail it) -- it does not re-prove the
     * cancel step's permit count, which that step already pins.
     */
    @Test
    void chatS6CancelReleaseRecoverMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("chat", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "chat-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.WEBFLUX, fixture, cancel);
        assertCancelPins("webflux/chat-s6-cancel", cancel.path("expected"), cancelled, Engine.WEBFLUX, recoveryText);
        Result next = drive(Engine.WEBFLUX, fixture, recovery);
        assertExpected("webflux/chat-s6-recovery", recovery.path("expected"), next);
        assertOutboundPreservesRequest("webflux/chat-s6-recovery", recovery.path("request"), next.upstreamBody());
        // Non-vacuity guard for the cancel signal above: the observer must count ONLY real client
        // cancels. A normal completion that also "cancelled" would make upstream_connection_released
        // pass for the wrong reason.
        assertEquals(0, next.upstreamCancels(),
                "webflux/chat-s6-recovery: a normal completion must NOT cancel the upstream transport");
    }

    /** chat s6 on the REAL servlet plane: the client disconnect is a failed flush, as a container does. */
    @Test
    void chatS6CancelReleaseRecoverMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("chat", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "chat-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.VT, fixture, cancel);
        assertCancelPins("vt/chat-s6-cancel", cancel.path("expected"), cancelled, Engine.VT, recoveryText);
        Result next = drive(Engine.VT, fixture, recovery);
        assertExpected("vt/chat-s6-recovery", recovery.path("expected"), next);
        assertOutboundPreservesRequest("vt/chat-s6-recovery", recovery.path("request"), next.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d3: messages s1 non-stream multi-turn

    @Test
    void messagesS1NonStreamMultiTurnMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/messages-s1", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s1", initial.path("request"), result.upstreamBody());
    }

    @Test
    void messagesS1NonStreamMultiTurnMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 1);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/messages-s1", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s1", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d3: messages s2 stream text + usage

    @Test
    void messagesS2StreamTextAndUsageMatchTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/messages-s2", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s2", initial.path("request"), result.upstreamBody());
    }

    @Test
    void messagesS2StreamTextAndUsageMatchTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 2);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/messages-s2", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s2", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d4: messages s3 tool call -> client executes -> continuation

    /**
     * messages s3: each step is an INDEPENDENT controlled-upstream request (no server-side conversation
     * storage), so both steps are driven separately. The entry codec re-encodes the DeepSeek tool_call as
     * an Anthropic {@code tool_use} content block; the continuation rebuilds the history with an assistant
     * tool_use + a user tool_result, which the codec splits back to assistant tool_calls + an INDEPENDENT
     * tool message + the trailing user text on the DeepSeek Chat upstream (matrix §5.3/§6.4). The captured
     * upstream body must STILL be DeepSeek Chat shape while the client receives the messages entry protocol.
     */
    @Test
    void messagesS3ToolCallContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/messages-s3-initial", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s3-initial",
                initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/messages-s3-continuation", continuation.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void messagesS3ToolCallContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 3);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/messages-s3-initial", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s3-initial",
                initial.path("request"), result.upstreamBody());
        JsonNode continuation = step(fixture, "continuation");
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/messages-s3-continuation", continuation.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s3-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d4b: messages s4 thinking output -> stateful continuation

    /**
     * messages s4: step1 streams a thinking block then the answer; step2 carries the reasoning state
     * back. Each step is an INDEPENDENT controlled-upstream request (no server-side conversation
     * storage). The entry codec re-encodes the DeepSeek reasoning_content as an Anthropic thinking
     * content block and mints a continuation envelope (signature, llmgw1:) bound to the runtime project
     * + reasoning text. The continuation rebuilds the history with an assistant thinking + text block,
     * which the codec maps back to ONE assistant message carrying reasoning_content + content on the
     * DeepSeek Chat upstream (matrix §5.3/§6.4/§7.3). The captured upstream body must STILL be DeepSeek
     * Chat shape while the client receives the messages entry protocol.
     *
     * <p>The fixture's pre-minted signature binds projectId="fixture-project", but the runtime uses the
     * degenerate identity mapping (tenantId=projectId=keyId="sk-test"), so the continuation substitutes
     * the RUNTIME-minted signature captured from step1's signature_delta (the fixture notes explicitly
     * permit this: verify() binds projectId + reasoning text SHA256, not the runtime item id).
     */
    @Test
    void messagesS4ThinkingContinuationMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/messages-s4-initial", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s4-initial",
                initial.path("request"), result.upstreamBody());
        String token = captureRuntimeThinkingSignature("webflux/messages-s4-initial", result.body());
        JsonNode continuation = substituteThinkingSignature(step(fixture, "continuation"), token);
        Result next = drive(Engine.WEBFLUX, fixture, continuation);
        assertExpected("webflux/messages-s4-continuation", continuation.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    @Test
    void messagesS4ThinkingContinuationMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 4);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/messages-s4-initial", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s4-initial",
                initial.path("request"), result.upstreamBody());
        String token = captureRuntimeThinkingSignature("vt/messages-s4-initial", result.body());
        JsonNode continuation = substituteThinkingSignature(step(fixture, "continuation"), token);
        Result next = drive(Engine.VT, fixture, continuation);
        assertExpected("vt/messages-s4-continuation", continuation.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s4-continuation",
                continuation.path("request"), next.upstreamBody());
    }

    /**
     * Capture the runtime-minted continuation envelope from step1's SSE thinking block: the
     * signature_delta frame's signature (closeThinking mints ctx.envelope().mint(projectId, itemId,
     * thinkingText) into a signature_delta). The token must be non-empty and carry the llmgw1:
     * namespace, otherwise the substitution is void. Messages has NO response.completed frame, so this
     * reads the content_block_delta/signature_delta shape, not the responses reasoning output item.
     */
    private static String captureRuntimeThinkingSignature(String where, String sseBody) {
        List<SseFrame> frames = parseFrames(sseBody);
        assertFalse(frames.isEmpty(), where + ": the stream must carry frames to capture the signature");
        String token = null;
        for (SseFrame frame : frames) {
            JsonNode payload = readTree(frame.data(), where);
            if ("content_block_delta".equals(payload.path("type").asString())
                    && "signature_delta".equals(payload.path("delta").path("type").asString())) {
                JsonNode signature = payload.path("delta").path("signature");
                assertTrue(signature.isString(),
                        where + ": the signature_delta must carry a string signature, got " + signature);
                token = signature.asString();
            }
        }
        assertTrue(token != null && !token.isEmpty(),
                where + ": a runtime thinking signature envelope must have been minted");
        assertTrue(token.startsWith("llmgw1:"),
                where + ": the captured signature must carry the llmgw1: namespace, got " + token);
        return token;
    }

    /**
     * Deep-copy the continuation step and substitute the runtime-minted signature into its assistant
     * thinking block, keeping the thinking plaintext unchanged (verify binds the reasoning SHA256). The
     * returned step's request is what gets driven AND what the outbound fidelity assertion compares
     * against. Mirrors substituteReasoningToken's discipline but uses the messages content-block shape.
     */
    private static JsonNode substituteThinkingSignature(JsonNode step, String token) {
        ObjectNode copy = (ObjectNode) step.deepCopy();
        JsonNode messages = copy.path("request").path("messages");
        assertTrue(messages.isArray(), "continuation request.messages must be an array, got " + messages);
        int replaced = 0;
        for (JsonNode message : messages) {
            if (!"assistant".equals(message.path("role").asString())) {
                continue;
            }
            JsonNode content = message.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode block : content) {
                if (!"thinking".equals(block.path("type").asString())) {
                    continue;
                }
                ObjectNode thinkingBlock = (ObjectNode) block;
                String originalToken = thinkingBlock.path("signature").asString();
                // Only the signature is swapped; the thinking plaintext is left untouched because
                // envelope.verify binds its SHA256 -- the continuation drive enforces that end-to-end
                // (an altered plaintext fails verify -> 400), so no local self-check is needed here.
                thinkingBlock.put("signature", token);
                assertNotEquals(originalToken, token,
                        "the runtime signature must differ from the fixture's pre-minted wrong-project token");
                replaced++;
            }
        }
        assertEquals(1, replaced, "the continuation must carry exactly one thinking block to substitute into");
        return copy;
    }

    // ---------------------------------------------------------------- slice d4c: messages s5 inline image understanding

    /**
     * messages s5: a user turn carrying a text block + an inline {@code image} block whose source is
     * {@code base64} (media_type + data). The entry codec decodes it into an ImagePart with detail ALWAYS
     * null (the Messages image block has no detail key), and the DeepSeek Chat upstream encode rebuilds it
     * as a chat-style {type:image_url,image_url:{url:"data:<media_type>;base64,<data>"}} part with NO detail
     * key. Because the user turn has TWO parts, the upstream content stays an ARRAY (matrix §5.2) while the
     * client receives the messages entry protocol. The marker stays verbatim in the upstream text part, so
     * the controlled mock still branches on it. The image.accepted pin is observed on the CAPTURED OUTBOUND
     * body against the independent fixture media_type + sha256, never recomputed from gateway code.
     */
    @Test
    void messagesS5ImageUnderstandingMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.WEBFLUX, fixture, initial);
        assertExpected("webflux/messages-s5", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s5", initial.path("request"), result.upstreamBody());
    }

    @Test
    void messagesS5ImageUnderstandingMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 5);
        JsonNode initial = step(fixture, "initial");
        Result result = drive(Engine.VT, fixture, initial);
        assertExpected("vt/messages-s5", initial.path("expected"), result, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s5", initial.path("request"), result.upstreamBody());
    }

    // ---------------------------------------------------------------- slice d4d: messages s6 cancel -> release -> recover

    /**
     * messages s6: cancel mid-stream -> release -> recover, on the REAL reactive plane. The cancel step
     * stops reading right after the first messages content frame (content_block_delta / text_delta) while
     * the controlled upstream stalls for 30s; its own active_permits_after_cancel==0 pin proves the
     * reservation was released. The recovery step then drives a NORMAL request on a FRESH chain/mock in
     * the SAME test, proving the disconnect left the route healthy -- it does not re-prove the cancel
     * step's permit count, which that step already pins. The entry codec re-encodes back to Anthropic
     * messages while the captured upstream body stays DeepSeek Chat shape.
     */
    @Test
    void messagesS6CancelReleaseRecoverMatchesTheFixturePinsOnWebflux() throws Exception {
        JsonNode fixture = fixture("messages", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "messages-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.WEBFLUX, fixture, cancel);
        assertMessagesCancelEntryProtocol("webflux/messages-s6-cancel", cancelled.body(),
                recovery.path("expected").path("sse"));
        assertCancelPins("webflux/messages-s6-cancel", cancel.path("expected"), cancelled, Engine.WEBFLUX,
                recoveryText, "messages");
        Result next = drive(Engine.WEBFLUX, fixture, recovery);
        assertExpected("webflux/messages-s6-recovery", recovery.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("webflux/messages-s6-recovery", recovery.path("request"), next.upstreamBody());
        // Non-vacuity guard for the cancel signal above: the observer must count ONLY real client
        // cancels. A normal completion that also "cancelled" would make upstream_connection_released
        // pass for the wrong reason.
        assertEquals(0, next.upstreamCancels(),
                "webflux/messages-s6-recovery: a normal completion must NOT cancel the upstream transport");
    }

    /** messages s6 on the REAL servlet plane: the client disconnect is a failed flush, as a container does. */
    @Test
    void messagesS6CancelReleaseRecoverMatchesTheFixturePinsOnVirtualThreads() throws Exception {
        JsonNode fixture = fixture("messages", 6);
        JsonNode cancel = step(fixture, "cancel");
        JsonNode recovery = step(fixture, "recovery");
        String recoveryText = pinString(recovery.path("expected").path("text"), "equals", "messages-s6-recovery");
        CancelResult cancelled = driveCancel(Engine.VT, fixture, cancel);
        assertMessagesCancelEntryProtocol("vt/messages-s6-cancel", cancelled.body(),
                recovery.path("expected").path("sse"));
        assertCancelPins("vt/messages-s6-cancel", cancel.path("expected"), cancelled, Engine.VT,
                recoveryText, "messages");
        Result next = drive(Engine.VT, fixture, recovery);
        assertExpected("vt/messages-s6-recovery", recovery.path("expected"), next, "messages");
        assertOutboundPreservesMessagesRequest("vt/messages-s6-recovery", recovery.path("request"), next.upstreamBody());
        // No upstreamCancels guard here: the servlet plane is blocking (streamRawBlocking), so that
        // reactive counter is structurally 0 and asserting it would pin a constant. The cancel step's
        // upstream_connection_released is non-vacuous instead because the pinned 30s stall + the 10s
        // settle latch + finished==[false] force blockingExited to mean "left the loop on disconnect",
        // never a clean EOF.
    }

    /**
     * The cancel step must be observed on the MESSAGES entry protocol, not on a chat-shaped body. The
     * fixture's cancel request (model/messages/max_tokens/stream with a STRING content) is ALSO a valid
     * chat request, so a harness that mis-routes the probe to handleChat AND lets the frame predicate
     * fall through to chat's choices[0].delta.content would arm on a chat body and satisfy every cancel
     * pin -- a silently VACUOUS green that proves the chat contract while claiming messages. (With the
     * predicate already entry-aware, a handler-only mis-route instead never arms and trips the settle
     * latch; this guard is the independent protocol truth that catches the double-fallthrough case.)
     * Every literal here comes from the SAME fixture's recovery-step messages SSE pins (independent
     * truth); the observed bytes are the cancel step's client body: event+data framing, event type ==
     * data type, no chat choices[], and a content_block_delta carrying the pinned text_delta type. A
     * chat/responses body fails loudly.
     */
    private static void assertMessagesCancelEntryProtocol(String where, String body, JsonNode ssePins) {
        String pinWhere = where + " (recovery sse pins)";
        assertEquals("event+data", pinString(ssePins, "framing", pinWhere),
                pinWhere + ": only event+data framing is pinned for the messages entry");
        boolean eventTypeEqualsDataType = pinBool(ssePins, "event_type_equals_data_type", pinWhere);
        String deltaType = pinString(ssePins.path("text_block"), "delta_type", pinWhere);
        List<SseFrame> frames = completeFrames(body);
        assertFalse(frames.isEmpty(), where + ": the cancel probe must capture at least one complete frame");
        boolean contentSeen = false;
        for (SseFrame frame : frames) {
            int eventLines = 0;
            int dataLines = 0;
            String eventType = null;
            for (String line : frame.lines()) {
                if (line.startsWith("event:")) {
                    eventLines++;
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataLines++;
                } else {
                    throw new AssertionError(where + ": the cancelled body must use messages event+data framing,"
                            + " got line '" + line + "'");
                }
            }
            assertEquals(1, eventLines,
                    where + ": each cancelled frame must carry exactly one event: line (messages entry protocol)");
            assertEquals(1, dataLines,
                    where + ": each cancelled frame must carry exactly one data: line (messages entry protocol)");
            JsonNode payload = readTree(frame.data(), where);
            assertTrue(payload.path("choices").isMissingNode(),
                    where + ": the cancelled body must NOT be chat-shaped (choices[]), body=" + body);
            if (eventTypeEqualsDataType) {
                assertEquals(eventType, payload.path("type").asString(),
                        where + ": cancelled frame event type must equal its data payload type");
            }
            if ("content_block_delta".equals(payload.path("type").asString())
                    && deltaType.equals(payload.path("delta").path("type").asString())) {
                assertTrue(payload.path("delta").path("text").isString(),
                        where + ": a " + deltaType + " must carry a string text increment, got " + payload);
                contentSeen = true;
            }
        }
        assertTrue(contentSeen,
                where + ": the cancelled body must carry a messages " + deltaType + " content frame, body=" + body);
    }

    // ---------------------------------------------------------------- slice e: s7 controlled contract negatives

    @Test
    void chatS7ContractNegativesMatchTheFixtureErrorPinsOnWebflux() throws Exception {
        assertContractNegatives(Engine.WEBFLUX, "chat");
    }

    @Test
    void chatS7ContractNegativesMatchTheFixtureErrorPinsOnVirtualThreads() throws Exception {
        assertContractNegatives(Engine.VT, "chat");
    }

    @Test
    void chatS7AuthorizedDegradationSucceedsOnWebflux() throws Exception {
        assertAuthorizedDegradation(Engine.WEBFLUX, "chat");
    }

    @Test
    void chatS7AuthorizedDegradationSucceedsOnVirtualThreads() throws Exception {
        assertAuthorizedDegradation(Engine.VT, "chat");
    }

    @Test
    void responsesS7ContractNegativesMatchTheFixtureErrorPinsOnWebflux() throws Exception {
        assertContractNegatives(Engine.WEBFLUX, "responses");
    }

    @Test
    void responsesS7ContractNegativesMatchTheFixtureErrorPinsOnVirtualThreads() throws Exception {
        assertContractNegatives(Engine.VT, "responses");
    }

    @Test
    void responsesS7AuthorizedDegradationSucceedsOnWebflux() throws Exception {
        assertAuthorizedDegradation(Engine.WEBFLUX, "responses");
    }

    @Test
    void responsesS7AuthorizedDegradationSucceedsOnVirtualThreads() throws Exception {
        assertAuthorizedDegradation(Engine.VT, "responses");
    }

    @Test
    void messagesS7ContractNegativesMatchTheFixtureErrorPinsOnWebflux() throws Exception {
        assertContractNegatives(Engine.WEBFLUX, "messages");
    }

    @Test
    void messagesS7ContractNegativesMatchTheFixtureErrorPinsOnVirtualThreads() throws Exception {
        assertContractNegatives(Engine.VT, "messages");
    }

    @Test
    void messagesS7AuthorizedDegradationSucceedsOnWebflux() throws Exception {
        assertAuthorizedDegradation(Engine.WEBFLUX, "messages");
    }

    @Test
    void messagesS7AuthorizedDegradationSucceedsOnVirtualThreads() throws Exception {
        assertAuthorizedDegradation(Engine.VT, "messages");
    }

    /**
     * A pre-header capability rejection. The pinned STATUS is the behavioral core: a codec that wrongly
     * accepts the request answers 200 and this fails. The body must be the ENTRY's own error shape (an
     * exact key set, so no success terminal can leak in), carrying the stable code + field path + reason,
     * and the request must never have touched the upstream transport.
     */
    private static void assertErrorExpected(String where, JsonNode expectedError, Result result, String entry) {
        requireKnownKeys(where + ".error", expectedError, ERROR_PIN_KEYS);
        assertEquals(pinInt(expectedError, "status", where), result.status(),
                where + ": the controlled contract negative must be REJECTED; body=" + result.body());
        assertEquals(pinInt(expectedError, "active_permits", where), result.activePermits(),
                where + ": every acquired permit must be released");
        assertEquals(finishedPin(expectedError, where), result.finished(), where + ": governance ledger");
        assertEquals(pinInt(expectedError, "upstream_requests", where), result.upstreamRequests(),
                where + ": s7 negatives are pre-header rejections and must not call the upstream");
        assertNull(result.upstreamBody(), where + ": a pre-header rejection must never call the upstream");
        assertNull(result.upstreamPassHeaders(), where + ": a pre-header rejection must never build pass headers");

        String code = pinString(expectedError, "code", where);
        String param = pinString(expectedError, "param", where);
        String reason = pinString(expectedError, "reason", where);
        JsonNode body = readTree(result.body(), where);
        // Explicit entry dispatch: messages must NOT fall through to the OpenAI-shaped pins.
        switch (entry) {
            case "chat", "responses" -> {
                assertExactKeys(where + ".body", body, Set.of("error"));
                JsonNode error = body.path("error");
                assertExactKeys(where + ".body.error", error, OPENAI_ERROR_KEYS);
                assertEquals(code, error.path("code").asString(), where + ": stable capability code");
                assertEquals(param, error.path("param").asString(), where + ": field path");
                assertEquals(reason, error.path("message").asString(), where + ": reason");
                assertEquals(pinString(expectedError, "error_type", where), error.path("type").asString(),
                        where + ": wire error type");
            }
            case "messages" -> {
                assertExactKeys(where + ".body", body, Set.of("type", "error"));
                assertEquals("error", body.path("type").asString(), where + ": messages error envelope type");
                JsonNode error = body.path("error");
                assertExactKeys(where + ".body.error", error, MESSAGES_ERROR_KEYS);
                assertEquals(code, error.path("type").asString(), where + ": stable capability code");
                assertEquals(reason + " (field: " + param + ")", error.path("message").asString(),
                        where + ": messages folds the field path into the message text");
            }
            default -> throw new AssertionError(where + ": unknown entry '" + entry + "'");
        }
    }

    /**
     * Every s7 step except the authorized positive is a REJECTION: the real plane must answer the
     * pinned error status with the entry's own error shape. A production codec that wrongly accepts
     * the request answers 200 and this fails -- the status assertion is the behavioral core, not a
     * restatement of the codec unit tests. A file reference is pre-empted by the ENGINE ownership
     * guard (404, gateway-generic body) before conversion runs, so it pins resource_denial instead.
     */
    private void assertContractNegatives(Engine engine, String entry) throws Exception {
        JsonNode fixture = fixture(entry, 7);
        int negatives = 0;
        for (JsonNode step : fixture.path("steps")) {
            JsonNode expected = step.path("expected");
            JsonNode denial = expected.path("resource_denial");
            if (expected.path("error").isMissingNode() && denial.isMissingNode()) {
                continue;
            }
            String where = engineLabel(engine) + "/" + entry + "-s7-" + step.path("name").asString();
            Result result = drive(engine, fixture, step);
            if (denial.isMissingNode()) {
                assertErrorExpected(where, expected.path("error"), result, entry);
            } else {
                assertResourceDenialExpected(where, denial, result);
                // The ownership guard pre-empts conversion at drive level (404). The versioned expected.error
                // pin is the ENTRY CODEC's own pre-header rejection; assert it at codec level so it is not dead.
                assertCodecRejection(where, expected.path("error"), step.path("request"), entry);
            }
            negatives++;
        }
        // A silently empty loop (fixture lost its error pins) must FAIL, not pass vacuously.
        assertEquals(fixture.path("steps").size() - 1, negatives,
                entry + " s7: every step except degradation-authorized must be a contract negative");
    }

    /**
     * The engine's cross-route file-ownership guard fires BEFORE conversion, so the body is the
     * gateway-generic shape rather than the entry's. Pinned exactly (no code, no param) so the
     * divergence from the entry error contract stays visible instead of being silently accepted.
     */
    private static void assertResourceDenialExpected(String where, JsonNode denial, Result result) {
        requireKnownKeys(where + ".resource_denial", denial, RESOURCE_DENIAL_KEYS);
        assertEquals(pinInt(denial, "status", where), result.status(),
                where + ": an unowned file reference must be denied; body=" + result.body());
        assertEquals(pinInt(denial, "active_permits", where), result.activePermits(),
                where + ": every acquired permit must be released");
        assertEquals(finishedPin(denial, where), result.finished(), where + ": exactly one FAILED finish");
        assertEquals(pinInt(denial, "upstream_requests", where), result.upstreamRequests(),
                where + ": the ownership guard must deny before any upstream request");
        assertNull(result.upstreamBody(), where + ": the ownership guard must never call the upstream");
        assertNull(result.upstreamPassHeaders(), where + ": the ownership guard must never build pass headers");
        JsonNode body = readTree(result.body(), where);
        assertExactKeys(where + ".body", body, Set.of("error"));
        JsonNode error = body.path("error");
        assertExactKeys(where + ".body.error", error, Set.of("message", "type"));
        assertEquals(pinString(denial, "message", where), error.path("message").asString(), where + ": reason");
        assertEquals(pinString(denial, "error_type", where), error.path("type").asString(),
                where + ": gateway-generic error type");
    }

    /** Parse the versioned 'finished' ledger pin (an array of booleans); a malformed pin fails loudly. */
    private static List<Boolean> finishedPin(JsonNode block, String where) {
        JsonNode pin = block.path("finished");
        assertTrue(pin.isArray(), where + ": fixture pin 'finished' must be an array, got " + pin);
        List<Boolean> expected = new ArrayList<>();
        for (JsonNode flag : pin) {
            assertTrue(flag.isBoolean(), where + ": fixture pin 'finished' entries must be booleans, got " + flag);
            expected.add(flag.booleanValue());
        }
        return expected;
    }

    /**
     * A resource-denied step's expected.error pin is the ENTRY CODEC's own pre-header capability rejection,
     * which the engine ownership guard pre-empts at drive level (404). Decode the very same request with the
     * real codec and pin the exact status + code + field path + reason, so the versioned pin is asserted, not dead.
     */
    private static void assertCodecRejection(String where, JsonNode errorPin, JsonNode request, String entry)
            throws Exception {
        requireKnownKeys(where + ".error", errorPin, ERROR_PIN_KEYS);
        CapabilityContext context = new CapabilityContext(DRIVE_PROJECT_ID, DRIVE_PROJECT_ID,
                new DegradationAudit(DegradationGrant.parse(null)),
                new ContinuationEnvelope(DRIVE_CONTINUATION_KEY.getBytes(StandardCharsets.UTF_8)));
        String raw = MAPPER.writeValueAsString(request);
        CapabilityException error = assertThrows(CapabilityException.class,
                () -> entryCodec(entry).decode(raw, context),
                where + ": the entry codec must reject the file reference pre-header");
        assertEquals(pinInt(errorPin, "status", where), error.status(), where + ": codec rejection status");
        assertEquals(1, error.violations().size(), where + ": exactly one capability violation");
        Violation violation = error.violations().get(0);
        assertEquals(pinString(errorPin, "code", where), violation.code(), where + ": codec capability code");
        assertEquals(pinString(errorPin, "param", where), violation.fieldPath(), where + ": codec field path");
        assertEquals(pinString(errorPin, "reason", where), violation.reason(), where + ": codec reason");
    }

    /**
     * The authorized twin of the unauthorized rejection: byte-identical request, the grant header is
     * the ONLY difference. Must really succeed (200), really degrade on the outbound wire, really
     * record the degradation in the per-request audit, and never leak the grant header upstream.
     */
    private void assertAuthorizedDegradation(Engine engine, String entry) throws Exception {
        JsonNode fixture = fixture(entry, 7);
        JsonNode authorized = step(fixture, "degradation-authorized");
        JsonNode unauthorized = step(fixture, "degradation-unauthorized");
        assertEquals(unauthorized.path("request"), authorized.path("request"),
                entry + " s7: the grant header must be the only difference between the two degradation steps");
        assertTrue(unauthorized.path("headers").isMissingNode(),
                entry + " s7: the unauthorized twin must send NO grant header, so the grant is the only difference");
        String where = engineLabel(engine) + "/" + entry + "-s7-degradation-authorized";
        Result result = drive(engine, fixture, authorized);
        assertEquals(200, result.status(), where + ": authorized degradation must succeed; body=" + result.body());
        assertEquals(List.of(true), result.finished(), where + ": exactly one SUCCESS finish");
        assertEquals(0, result.activePermits(), where + ": every acquired permit must be released");

        JsonNode degradation = authorized.path("degradation");
        requireKnownKeys(where + ".degradation", degradation, DEGRADATION_KEYS);
        String header = pinString(degradation, "header", where);
        assertExpected(where, authorized.path("expected"), result, entry);
        JsonNode outbound = readTree(result.upstreamBody(), where);
        assertEquals(pinString(degradation, "outbound_equals", where),
                atPath(outbound, pinString(degradation, "outbound_field", where)).asString(),
                where + ": the authorized degradation must be visible on the outbound wire");
        Map<String, String> passHeaders = result.upstreamPassHeaders();
        assertNotNull(passHeaders, where + ": the upstream must have been called");
        for (String name : passHeaders.keySet()) {
            assertFalse(name.equalsIgnoreCase(header),
                    where + ": the caller's grant header must not be forwarded upstream, got " + passHeaders);
        }

        // PreparedConversion does not expose the audit, so the audit half of the contract is pinned
        // through the REAL entry codec with the grant parsed from the very same fixture header.
        String headerValue = grantHeaderValue(authorized.path("headers"), header, where);
        DegradationAudit audit = new DegradationAudit(DegradationGrant.parse(headerValue));
        CapabilityContext context = new CapabilityContext(DRIVE_PROJECT_ID, DRIVE_PROJECT_ID, audit,
                new ContinuationEnvelope(DRIVE_CONTINUATION_KEY.getBytes(StandardCharsets.UTF_8)));
        entryCodec(entry).decode(MAPPER.writeValueAsString(authorized.path("request")), context);
        List<AppliedDegradation> applied = audit.applied();
        assertEquals(1, applied.size(), where + ": exactly one degradation must be audited, got " + applied);
        assertEquals(pinString(degradation, "id", where), applied.get(0).degradationId(), where + ": audited id");
        assertEquals(pinString(degradation, "field_path", where), applied.get(0).fieldPath(),
                where + ": audited field path");
        assertFalse(applied.get(0).reason().isBlank(), where + ": the audit must carry a reason");
    }

    private static EntryCodec entryCodec(String entry) {
        return switch (entry) {
            case "chat" -> new ChatEntryCodec();
            case "responses" -> new ResponsesEntryCodec();
            case "messages" -> new MessagesEntryCodec();
            default -> throw new AssertionError("unknown entry '" + entry + "'");
        };
    }

    /** HTTP header names are case-insensitive; the fixture may pin either spelling of the grant. */
    private static String grantHeaderValue(JsonNode headers, String header, String where) {
        assertTrue(headers.isObject(), where + ": the authorized step must carry a headers object");
        String value = null;
        for (Map.Entry<String, JsonNode> entry : headers.properties()) {
            if (entry.getKey().equalsIgnoreCase(header)) {
                assertNull(value, where + ": the grant header must appear exactly once, got " + headers);
                value = pinString(headers, entry.getKey(), where);
            }
        }
        assertNotNull(value, where + ": no " + header + " grant header in " + headers);
        return value;
    }

    /** Dotted object path only; an array index in a pin is a fixture bug and must fail loudly. */
    private static JsonNode atPath(JsonNode root, String dottedPath) {
        assertFalse(dottedPath.contains("["), "outbound_field must be a dotted object path, got " + dottedPath);
        JsonNode node = root;
        for (String segment : dottedPath.split("\\.")) {
            node = node.path(segment);
        }
        return node;
    }

    private static void assertExactKeys(String where, JsonNode node, Set<String> expected) {
        assertTrue(node.isObject(), where + ": expected a JSON object, got " + node);
        Set<String> actual = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            actual.add(field.getKey());
        }
        assertEquals(expected, actual, where + ": exact key set (no success terminal may leak into an error body)");
    }

    // ---------------------------------------------------------------- slice b: mock resource lifecycle (close + instance isolation)

    /**
     * A multi-drive test (s3/s4) starts a fresh controlled mock per drive. The previous instance MUST
     * be destroyed when the next one starts, otherwise its listening socket is orphaned until JVM exit
     * (repo rule: resource changes must cover close + instance isolation). Observed on the REAL loopback
     * port, not an internal flag: once the second mock starts, the first port must refuse connections.
     */
    @Test
    void startMockDestroysThePreviousInstanceAndIsolatesPorts() throws Exception {
        startMock(4);
        int firstPort = port;
        assertTrue(isLoopbackPortOpen(firstPort), "the first mock must be listening");
        startMock(4);
        int secondPort = port;
        assertNotEquals(firstPort, secondPort, "each mock instance must bind its own port");
        assertFalse(isLoopbackPortOpen(firstPort),
                "the previous mock must be destroyed, not orphaned (listening socket leaked)");
        assertTrue(isLoopbackPortOpen(secondPort), "the current mock must be listening");
    }

    /** True iff something still accepts TCP connections on the loopback port (a live mock). */
    private static boolean isLoopbackPortOpen(int probePort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", probePort), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}