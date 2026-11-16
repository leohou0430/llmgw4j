package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.ConversionService;
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
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.12 slice 5: the conversion seam end-to-end against the REAL codecs and BOTH real data planes,
 * with a controlled upstream (no network). The engine-module seam tests use a fake conversion handle;
 * this is the composition proof that a real entry codec + real DeepSeek transcoder + real engine
 * accounting agree — in particular that a successfully converted responses/messages stream (whose
 * terminal is a whole {@code event:}+{@code data:} SSE event) is billed as a SUCCESS, not mistaken
 * for a truncation.
 */
class ConversionEngineIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String CALLER_MODEL = "client-model";
    private static final String UPSTREAM_MODEL = "deepseek-chat";
    private static final String USAGE = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";

    private static final String UPSTREAM_RESPONSE = """
            {"id":"chatcmpl-up","object":"chat.completion","created":1700000000,"model":"deepseek-chat",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""";

    private enum Engine { VT, WEBFLUX }

    /** What the controlled DeepSeek chat upstream emits before a clean EOF. */
    private enum UpstreamMode {
        /** delta + finish_reason + the [DONE] sentinel: a normal complete chat stream. */
        COMPLETE,
        /** delta only, then EOF: a silent truncation with no terminal at all. */
        TRUNCATED,
        /** delta + finish_reason, then EOF WITHOUT [DONE]: logically complete, sentinel missing. */
        FINISH_NO_DONE,
        /** role-only delta (the real DeepSeek first frame) then EOF: ZERO entry lines were produced. */
        ROLE_ONLY,
        /** delta + finish_reason, then a TRANSPORT ERROR (not a clean EOF): logically complete. */
        FINISH_THEN_ERROR,
        /** ONLY an SSE heartbeat comment then EOF: response committed (200) but ZERO entry data. */
        HEARTBEAT_ONLY,
        /** ONLY an SSE heartbeat comment then a TRANSPORT ERROR: committed (200), ZERO entry data. */
        HEARTBEAT_THEN_ERROR
    }

    private record Result(int status, String body, List<Boolean> finished) {
    }

    // ---------- entry requests ----------

    private static String entryRequest(ProtocolKind kind, boolean stream) {
        String model = "\"model\":\"" + CALLER_MODEL + "\"";
        return switch (kind) {
            case CHAT -> "{" + model + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"
                    + (stream ? ",\"stream\":true" : "") + "}";
            case RESPONSES -> "{" + model + ",\"input\":\"hi\"" + (stream ? ",\"stream\":true" : "") + "}";
            case MESSAGES -> "{" + model + ",\"max_tokens\":1024,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]" + (stream ? ",\"stream\":true" : "") + "}";
            default -> throw new IllegalArgumentException("unsupported entry kind " + kind);
        };
    }

    // ---------- controlled DeepSeek Chat upstream ----------

    private static String chunk(String deltaJson, String finishReason, String usageJson) {
        return "{\"id\":\"chatcmpl-up\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"" + UPSTREAM_MODEL + "\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + ",\"finish_reason\":" + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]"
                + (usageJson == null ? "" : ",\"usage\":" + usageJson) + "}";
    }

    private static List<String> streamLines(UpstreamMode mode) {
        String first = "data: " + chunk("{\"role\":\"assistant\",\"content\":\"hello\"}", null, null) + "\n";
        String finish = "data: " + chunk("{}", "stop", USAGE) + "\n";
        return switch (mode) {
            // A silent truncation: a delta, then EOF with no finish_reason and no [DONE].
            case TRUNCATED -> List.of(first);
            // Logically complete (finish_reason seen) but the [DONE] sentinel never arrives.
            case FINISH_NO_DONE -> List.of(first, finish);
            case COMPLETE -> List.of(first, finish, "data: [DONE]\n");
            // The real DeepSeek first frame announces only the role; the entry codec buffers it and
            // emits nothing, so this whole stream produces ZERO entry lines before EOF.
            case ROLE_ONLY -> List.of("data: " + chunk("{\"role\":\"assistant\"}", null, null) + "\n");
            // Same wire frames as FINISH_NO_DONE, but the upstream signals a transport error after
            // the finish chunk instead of a clean EOF (see ControlledUpstream).
            case FINISH_THEN_ERROR -> List.of(first, finish);
            // A lone heartbeat comment: it commits the response but carries no upstream chunk, so
            // the transcoder produces ZERO entry lines before the clean EOF.
            case HEARTBEAT_ONLY -> List.of(": keep-alive\n");
            // Same lone heartbeat, but the upstream signals a transport error instead of a clean EOF.
            case HEARTBEAT_THEN_ERROR -> List.of(": keep-alive\n");
        };
    }

    private static final class ControlledUpstream implements UpstreamProvider {
        final UpstreamMode mode;
        final List<UpstreamRequest> requests = new CopyOnWriteArrayList<>();

        ControlledUpstream(UpstreamMode mode) {
            this.mode = mode;
        }

        public String id() {
            return "controlled";
        }

        public String completeRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            listener.onHeaders(200, Map.of());
            return UPSTREAM_RESPONSE;
        }

        public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
            requests.add(request);
            sink.onHeaders(200, Map.of());
            for (String line : streamLines(mode)) {
                sink.onChunk(line);
            }
            if (mode == UpstreamMode.FINISH_THEN_ERROR || mode == UpstreamMode.HEARTBEAT_THEN_ERROR) {
                IllegalStateException reset = new IllegalStateException("upstream transport reset");
                sink.onError(reset);
                throw reset; // JdkHttpUpstream rethrows after the onError callback.
            }
            sink.onDone();
        }

        public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            List<String> lines = streamLines(mode);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicInteger index = new AtomicInteger();
                private volatile boolean cancelled;

                public void request(long n) {
                    if (cancelled) {
                        return;
                    }
                    listener.onHeaders(200, Map.of());
                    for (long i = 0; i < n && !cancelled; i++) {
                        int at = index.getAndIncrement();
                        if (at >= lines.size()) {
                            if (mode == UpstreamMode.FINISH_THEN_ERROR || mode == UpstreamMode.HEARTBEAT_THEN_ERROR) {
                                subscriber.onError(new IllegalStateException("upstream transport reset"));
                            } else {
                                subscriber.onComplete();
                            }
                            return;
                        }
                        subscriber.onNext(lines.get(at));
                    }
                }

                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }

    private static final class RecordingChain implements GovernanceChain {
        final List<Boolean> finished = new CopyOnWriteArrayList<>();
        final List<String> cached = new CopyOnWriteArrayList<>();

        public GatewayDecision enter(String token, ParsedRequest request, Route target) {
            return GatewayDecision.allow("tenant", request, target);
        }

        public Optional<String> lookupCache(String key, ParsedRequest request) {
            return Optional.empty();
        }

        public void cache(String key, ParsedRequest request, String body) {
            cached.add(body);
        }

        public void finish(GatewayContext context, boolean success) {
            finished.add(success);
        }

        public Map<String, Object> snapshot() {
            return Map.of();
        }
    }

    // ---------- real conversion service ----------

    private static ConversionService realService(ExtensionContext context) {
        context.external(GatewayProperties.class, new GatewayProperties());
        context.loadModule("conversion");
        context.seal();
        return context.get(ConversionService.class);
    }

    private static Route route() {
        return new Route("test", "*", CALLER_MODEL, UPSTREAM_MODEL, "http://unused",
                "sk-test", "", 1, List.of(), "chat");
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
                out.remove("authorization");
                out.remove("content-type");
                out.remove("content-length");
                out.remove("host");
                return out;
            }

            public List<String> toClient(Map<String, List<String>> values) {
                return List.of();
            }
        };
    }

    private static GatewayProperties properties() {
        var properties = new GatewayProperties();
        properties.setMaxConcurrentRequests(10);
        properties.getUpstream().setHeartbeatIntervalMs(0);
        return properties;
    }

    /** Drive one request through the chosen real data plane with the real codecs. */
    private static Result run(Engine engine, ProtocolKind kind, boolean stream, UpstreamMode mode) throws Exception {
        Route route = route();
        ControlledUpstream upstream = new ControlledUpstream(mode);
        RecordingChain chain = new RecordingChain();
        var tasks = new InMemoryTaskRouteSticky(10, 60);
        var properties = properties();
        var router = router(route);
        var headers = allowAllHeaders();
        String body = entryRequest(kind, stream);
        try (var context = new ExtensionContext()) {
            ConversionService conversion = realService(context);
            return engine == Engine.VT
                    ? runVt(kind, body, conversion, chain, router, upstream, properties, headers, tasks)
                    : runWebflux(kind, body, conversion, chain, router, upstream, properties, headers, tasks);
        }
    }

    private static Result runVt(ProtocolKind kind, String body, ConversionService conversion,
                                RecordingChain chain, Router router, ControlledUpstream upstream,
                                GatewayProperties properties, HeaderPolicy headers,
                                InMemoryTaskRouteSticky tasks) throws Exception {
        int[] status = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headerMap = new LinkedHashMap<>();
        headerMap.put("Authorization", "Bearer test-token");
        headerMap.put("Content-Type", "application/json");

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
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                ConversionEngineIntegrationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> servletIn;
                    case "getHeader" -> headerMap.get((String) args[0]);
                    case "getHeaderNames" -> Collections.enumeration(headerMap.keySet());
                    default -> defaultValue(method.getReturnType());
                });
        boolean[] committed = {false};
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
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                ConversionEngineIntegrationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setStatus" -> {
                        status[0] = (Integer) args[0];
                        yield null;
                    }
                    case "getStatus" -> status[0];
                    case "getOutputStream" -> servletOut;
                    case "isCommitted" -> committed[0];
                    default -> defaultValue(method.getReturnType());
                });
        var controller = new VirtualThreadGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        switch (kind) {
            case CHAT -> controller.handleChat(request, response);
            case RESPONSES -> controller.handleResponses(request, response);
            case MESSAGES -> controller.handleMessages(request, response);
            default -> throw new IllegalArgumentException("unsupported entry kind " + kind);
        }
        return new Result(status[0], out.toString(StandardCharsets.UTF_8), List.copyOf(chain.finished));
    }

    private static Result runWebflux(ProtocolKind kind, String body, ConversionService conversion,
                                     RecordingChain chain, Router router, ControlledUpstream upstream,
                                     GatewayProperties properties, HeaderPolicy headers,
                                     InMemoryTaskRouteSticky tasks) {
        var controller = new WebfluxGatewayController(chain, router, upstream,
                CircuitBreaker.noop(), properties, headers, tasks, conversion);
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/" + kind.wireName())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-token")
                .body(body);
        MockServerHttpResponse response = new MockServerHttpResponse();
        switch (kind) {
            case CHAT -> controller.handleChat(request, response).block(Duration.ofSeconds(5));
            case RESPONSES -> controller.handleResponses(request, response).block(Duration.ofSeconds(5));
            case MESSAGES -> controller.handleMessages(request, response).block(Duration.ofSeconds(5));
            default -> throw new IllegalArgumentException("unsupported entry kind " + kind);
        }
        String responseBody = response.getBodyAsString().block(Duration.ofSeconds(5));
        return new Result(response.getStatusCode().value(), responseBody, List.copyOf(chain.finished));
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

    private static long count(String haystack, String needle) {
        long n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ---------- non-streaming round trips ----------

    @Test
    void nonStreamConversionReturnsTheEntryShapeAndBillsSuccessOnBothEngines() throws Exception {
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, false, UpstreamMode.COMPLETE);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where);
                assertEquals(List.of(true), result.finished(), where + ": a converted completion bills success");
                JsonNode entry = MAPPER.readTree(result.body());
                assertEquals(CALLER_MODEL, entry.path("model").asString(), where + ": caller model echoed");
                switch (kind) {
                    case CHAT -> {
                        assertEquals("chat.completion", entry.path("object").asString(), where);
                        assertEquals("hello there",
                                entry.path("choices").get(0).path("message").path("content").asString(), where);
                        assertEquals(5, entry.path("usage").path("total_tokens").asInt(), where);
                    }
                    case RESPONSES -> {
                        assertEquals("response", entry.path("object").asString(), where);
                        assertEquals("completed", entry.path("status").asString(), where);
                        assertEquals("hello there",
                                entry.path("output").get(0).path("content").get(0).path("text").asString(), where);
                    }
                    case MESSAGES -> {
                        assertEquals("message", entry.path("type").asString(), where);
                        assertEquals("end_turn", entry.path("stop_reason").asString(), where);
                        assertEquals("hello there", entry.path("content").get(0).path("text").asString(), where);
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
    }

    // ---------- streaming: the multi-line terminal regression ----------

    @Test
    void streamConversionEmitsExactlyOneEntryTerminalAndBillsSuccessOnBothEngines() throws Exception {
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.COMPLETE);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where);
                assertEquals(List.of(true), result.finished(),
                        where + ": a completed converted stream must bill SUCCESS, not a truncation; body=" + result.body());
                String body = result.body();
                assertTrue(body.contains("hello"), where + ": the delta reached the client: " + body);
                switch (kind) {
                    case CHAT -> {
                        assertEquals(1, count(body, "data: [DONE]"), where + ": exactly one [DONE]: " + body);
                        assertEquals(1, count(body, "\"finish_reason\":\"stop\""), where);
                    }
                    case RESPONSES -> {
                        assertEquals(1, count(body, "event: response.completed"),
                                where + ": exactly one responses terminal: " + body);
                        assertEquals(0, count(body, "[DONE]"), where + ": responses never uses [DONE]: " + body);
                    }
                    case MESSAGES -> {
                        assertEquals(1, count(body, "event: message_stop"),
                                where + ": exactly one messages terminal: " + body);
                        assertEquals(0, count(body, "[DONE]"), where + ": messages never uses [DONE]: " + body);
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
    }

    @Test
    void truncatedStreamEmitsAnEntryErrorAndBillsFailureOnBothEngines() throws Exception {
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.TRUNCATED);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where);
                assertEquals(List.of(false), result.finished(), where + ": a truncation bills failure");
                String body = result.body();
                assertTrue(body.contains("\"error\"") || body.contains("event: error"),
                        where + ": an entry error event is emitted: " + body);
                assertEquals(0, count(body, "data: [DONE]"), where + ": never a fake success terminal: " + body);
                assertEquals(0, count(body, "event: response.completed"), where);
                assertEquals(0, count(body, "event: message_stop"), where);
                // Count error EVENTS, not the "error" substring: a messages error payload
                // {"type":"error","error":{...}} legitimately contains it twice.
                String errorEvent = kind == ProtocolKind.CHAT ? "data: {\"error\"" : "event: error";
                assertEquals(1, count(body, errorEvent), where + ": exactly one error event, no double error: " + body);
            }
        }
    }

    @Test
    void heartbeatOnlyConvertedStreamRendersTheEntryProtocolErrorOnBothEngines() throws Exception {
        // The upstream sends ONLY an SSE heartbeat comment then EOF. The comment commits the response
        // (200) but produces ZERO entry data, so the pre-header 502 path is no longer available. Both
        // engines must render the ENTRY-protocol error via the transcoder -- not the generic
        // ProtocolCodec.errorEvent, which adds a chat 'event:' line + [DONE] and a non-entry payload
        // ({"error":{"type":"gateway_error"}}) for responses/messages. Bills failure, exactly one entry
        // error, no fake success terminal.
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.HEARTBEAT_ONLY);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where + ": the heartbeat already committed the response");
                assertEquals(List.of(false), result.finished(),
                        where + ": a heartbeat-only truncation bills failure; body=" + result.body());
                String body = result.body();
                assertEquals(0, count(body, "data: [DONE]"), where + ": never a fake success terminal: " + body);
                assertEquals(0, count(body, "event: response.completed"), where);
                assertEquals(0, count(body, "event: message_stop"), where);
                switch (kind) {
                    case CHAT -> {
                        // A chat entry error is a bare data frame: no 'event:' line and no [DONE].
                        assertEquals(0, count(body, "event: error"),
                                where + ": chat must not carry an event line: " + body);
                        assertEquals(1, count(body, "data: {\"error\""),
                                where + ": exactly one chat entry error: " + body);
                    }
                    case RESPONSES, MESSAGES -> {
                        assertEquals(1, count(body, "event: error"),
                                where + ": exactly one entry error event: " + body);
                        // The entry codec renders a top-level {"type":"error",...}; the generic gateway
                        // errorJson nests {"error":{"type":"gateway_error"}} and lacks this marker.
                        assertTrue(body.contains("\"type\":\"error\""),
                                where + ": entry-protocol error payload, not the generic gateway shape: " + body);
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
    }

    @Test
    void heartbeatThenTransportErrorRendersTheEntryProtocolErrorOnBothEngines() throws Exception {
        // Same lone-heartbeat committed stream as above, but the upstream signals a TRANSPORT ERROR
        // instead of a clean EOF. The response is already committed (200) with zero entry data, so both
        // engines must render the ENTRY-protocol error via the transcoder (never the generic gateway
        // shape) and bill failure. webflux previously routed this through conversionFirstChunkError's
        // committed branch, which emitted the generic ProtocolCodec.errorEvent.
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.HEARTBEAT_THEN_ERROR);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where + ": the heartbeat already committed the response");
                assertEquals(List.of(false), result.finished(),
                        where + ": a heartbeat-only transport error bills failure; body=" + result.body());
                String body = result.body();
                assertEquals(0, count(body, "data: [DONE]"), where + ": never a fake success terminal: " + body);
                assertEquals(0, count(body, "event: response.completed"), where);
                assertEquals(0, count(body, "event: message_stop"), where);
                switch (kind) {
                    case CHAT -> {
                        assertEquals(0, count(body, "event: error"),
                                where + ": chat must not carry an event line: " + body);
                        assertEquals(1, count(body, "data: {\"error\""),
                                where + ": exactly one chat entry error: " + body);
                    }
                    case RESPONSES, MESSAGES -> {
                        assertEquals(1, count(body, "event: error"),
                                where + ": exactly one entry error event: " + body);
                        assertTrue(body.contains("\"type\":\"error\""),
                                where + ": entry-protocol error payload, not the generic gateway shape: " + body);
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
    }

    @Test
    void chatFinishWithoutTheDoneSentinelStillBillsSuccessOnBothEngines() throws Exception {
        // finish_reason is the logical chat terminal; [DONE] is only a sentinel. If the upstream
        // closes cleanly after finish_reason but before [DONE], the transcoder's idempotent
        // onUpstreamComplete still emits the entry [DONE] once. Both engines must agree (parity):
        // VT previously gated onDone on terminalSeen() and mis-billed this as a truncation.
        for (Engine engine : Engine.values()) {
            Result result = run(engine, ProtocolKind.CHAT, true, UpstreamMode.FINISH_NO_DONE);
            String where = engine + "/CHAT";
            assertEquals(200, result.status(), where);
            assertEquals(List.of(true), result.finished(),
                    where + ": finish_reason completes the stream even without [DONE]; body=" + result.body());
            assertEquals(1, count(result.body(), "data: [DONE]"),
                    where + ": the transcoder emits the [DONE] terminal exactly once: " + result.body());
            assertEquals(1, count(result.body(), "\"finish_reason\":\"stop\""), where);
        }
    }

    @Test
    void finishThenTransportErrorStillCompletesTheEntryStreamOnBothEngines() throws Exception {
        // The upstream delivered its semantic terminal (finish_reason / response.completed /
        // message_stop) and THEN the transport reset. The response is genuinely complete, so this must
        // behave exactly like a clean EOF after the terminal: emit the entry terminal once and bill
        // SUCCESS. Previously the chat encoder's onError returned empty once terminalSeen latched, so
        // chat got neither [DONE] nor an error event and mis-billed a failure -- unlike finish-then-EOF.
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.FINISH_THEN_ERROR);
                String where = engine + "/" + kind;
                assertEquals(200, result.status(), where);
                assertEquals(List.of(true), result.finished(),
                        where + ": a delivered terminal completes the stream even if the transport then resets; body="
                                + result.body());
                String body = result.body();
                switch (kind) {
                    case CHAT -> {
                        assertEquals(1, count(body, "data: [DONE]"), where + ": exactly one [DONE]: " + body);
                        assertEquals(1, count(body, "\"finish_reason\":\"stop\""), where);
                    }
                    case RESPONSES -> {
                        assertEquals(1, count(body, "event: response.completed"),
                                where + ": exactly one responses terminal: " + body);
                        assertEquals(0, count(body, "[DONE]"), where);
                    }
                    case MESSAGES -> {
                        assertEquals(1, count(body, "event: message_stop"),
                                where + ": exactly one messages terminal: " + body);
                        assertEquals(0, count(body, "[DONE]"), where);
                    }
                    default -> throw new IllegalStateException();
                }
                String errorEvent = kind == ProtocolKind.CHAT ? "data: {\"error\"" : "event: error";
                assertEquals(0, count(body, errorEvent),
                        where + ": no error event after a delivered terminal: " + body);
            }
        }
    }

    @Test
    void roleOnlyDeltaThenEofIsAPreHeaderErrorOnBothEngines() throws Exception {
        // The real DeepSeek first frame can be a role-only delta that the entry codec buffers without
        // emitting any entry line. If the upstream then EOFs, ZERO entry content reached the client and
        // the response was never committed: both engines must render the SAME pre-header 502 + entry
        // error JSON. webflux previously flipped dataSeen on the raw upstream line and answered 200
        // in-band while VT answered 502 -- an observable parity break on the identical controlled state.
        for (Engine engine : Engine.values()) {
            for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
                Result result = run(engine, kind, true, UpstreamMode.ROLE_ONLY);
                String where = engine + "/" + kind;
                assertEquals(502, result.status(),
                        where + ": zero entry output before EOF is a pre-header bad gateway; body=" + result.body());
                assertEquals(List.of(false), result.finished(), where + ": a truncation bills failure");
                assertTrue(result.body().contains("error"),
                        where + ": an entry error is rendered: " + result.body());
                assertEquals(0, count(result.body(), "data: [DONE]"), where + ": never a fake success terminal");
                assertEquals(0, count(result.body(), "event: response.completed"), where);
                assertEquals(0, count(result.body(), "event: message_stop"), where);
            }
        }
    }

    @Test
    void convertedUpstreamRequestCarriesTheDeepSeekPayloadAndCallerModelOnBothEngines() throws Exception {
        for (Engine engine : Engine.values()) {
            UpstreamRequest sent = captureUpstreamRequest(engine);
            assertEquals("/chat/completions", sent.path(), engine + ": upstream path is the DeepSeek chat path");
            assertEquals(CALLER_MODEL, sent.model(), engine + ": no second target-model rewrite");
            JsonNode payload = MAPPER.readTree(sent.rawJson());
            assertEquals(UPSTREAM_MODEL, payload.path("model").asString(), engine + ": codec rewrote the model");
            assertEquals("hi", payload.path("messages").get(0).path("content").asString(),
                    engine + ": entry user message preserved");
        }
    }

    private static UpstreamRequest captureUpstreamRequest(Engine engine) throws Exception {
        Route route = route();
        ControlledUpstream upstream = new ControlledUpstream(UpstreamMode.COMPLETE);
        RecordingChain chain = new RecordingChain();
        var tasks = new InMemoryTaskRouteSticky(10, 60);
        String body = entryRequest(ProtocolKind.CHAT, false);
        try (var context = new ExtensionContext()) {
            ConversionService conversion = realService(context);
            Result result = engine == Engine.VT
                    ? runVt(ProtocolKind.CHAT, body, conversion, chain, router(route), upstream,
                            properties(), allowAllHeaders(), tasks)
                    : runWebflux(ProtocolKind.CHAT, body, conversion, chain, router(route), upstream,
                            properties(), allowAllHeaders(), tasks);
            assertEquals(200, result.status(), engine + ": capture run succeeded");
        }
        return upstream.requests.get(upstream.requests.size() - 1);
    }
}