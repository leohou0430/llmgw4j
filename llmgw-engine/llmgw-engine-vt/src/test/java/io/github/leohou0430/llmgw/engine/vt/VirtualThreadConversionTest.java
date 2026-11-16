package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
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
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.12 virtual-thread seam against a CONTROLLED upstream (no network): the conversion handle is a
 * fake so this module keeps depending on conversion-api only. Real-codec round trips live in
 * llmgw-app. Mirrors the webflux seam test; engine-vt has no spring-test, so it uses the Proxy
 * servlet stubs from {@code FileReferenceOwnershipTest}.
 */
class VirtualThreadConversionTest {

    static final String CALLER_MODEL = "client-model";
    static final String CHAT_BODY = "{\"model\":\"" + CALLER_MODEL
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String STREAM_BODY = "{\"model\":\"" + CALLER_MODEL + "\",\"stream\":true,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String PREPARED_JSON = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String UPSTREAM_BODY = "{\"id\":\"up\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    static final String ENTRY_BODY = "{\"id\":\"entry\",\"model\":\"" + CALLER_MODEL + "\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    static final String ENTRY_DELTA = "data: {\"id\":\"e\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}";
    static final String UPSTREAM_DELTA = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n";

    @Test
    void nonStreamConversionSendsThePreparedPayloadAndReturnsTheEntryBody() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(CHAT_BODY, fake);
        harness.upstream.nonStreamBody = UPSTREAM_BODY;
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(ENTRY_BODY, harness.body.toString(StandardCharsets.UTF_8));
        UpstreamRequest sent = harness.upstream.last();
        assertEquals(PREPARED_JSON, sent.rawJson(), "the prepared payload reaches the wire verbatim");
        assertEquals("/chat/completions", sent.path());
        assertEquals(CALLER_MODEL, sent.model(), "no second target-model rewrite");
        assertEquals(1, fake.prepareCalls.get());
        assertEquals(1, fake.prepared.transcodeCalls.get());
        assertEquals(UPSTREAM_BODY, fake.prepared.transcodedFrom);
        assertEquals(1, fake.prepared.closeCalls.get(), "the handle must be closed");
        assertEquals(1, harness.chain.cached.size());
        assertEquals(ENTRY_BODY, harness.chain.cached.get(0).body());
        assertEquals(List.of(true), harness.chain.finished);
    }

    @Test
    void streamConversionTranscodesUpstreamLinesAndEmitsExactlyOneEntryTerminal() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: [DONE]");
        fake.prepared.terminalAfterComplete = true;
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of(UPSTREAM_DELTA, "data: [DONE]\n");
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(ENTRY_DELTA + "\n\n" + "data: [DONE]\n\n", harness.body.toString(StandardCharsets.UTF_8));
        UpstreamRequest sent = harness.upstream.last();
        assertEquals(PREPARED_JSON, sent.rawJson());
        assertEquals("/chat/completions", sent.path());
        assertEquals(CALLER_MODEL, sent.model());
        assertTrue(sent.stream());
        assertEquals(1, fake.prepared.transcoderCalls.get());
        assertEquals(1, fake.prepared.closeCalls.get());
        assertEquals(List.of(true), harness.chain.finished);
    }

    @Test
    void streamTruncationEmitsAnEntryErrorNeverAFakeSuccessOrADoubleError() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: {\"error\":{\"message\":\"truncated\",\"type\":\"server_error\"}}");
        fake.prepared.terminalAfterComplete = true;
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of(UPSTREAM_DELTA);
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        String body = harness.body.toString(StandardCharsets.UTF_8);
        assertEquals(ENTRY_DELTA + "\n\n"
                + "data: {\"error\":{\"message\":\"truncated\",\"type\":\"server_error\"}}\n\n", body);
        assertTrue(!body.contains("[DONE]"), "a truncation must never fabricate a success terminal");
        assertEquals(1, countOccurrences(body, "\"error\""), "exactly one entry error, no double error");
        assertEquals(List.of(false), harness.chain.finished, "a truncation is billed as a failure");
        assertEquals(1, fake.prepared.closeCalls.get());
    }

    static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void preHeaderCapabilityErrorUsesItsStatusAndEntryShape() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepareFailure = new CapabilityException(CapabilityException.INVALID_VALUE, 400, "model", "bad");
        var harness = new Harness(CHAT_BODY, fake);
        harness.dispatch();
        assertEquals(400, harness.status[0]);
        assertEquals(fake.errorJson, harness.body.toString(StandardCharsets.UTF_8));
        assertTrue(harness.upstream.requests.isEmpty(), "no upstream call on a pre-header failure");
        assertEquals(List.of(false), harness.chain.finished);
    }

    @Test
    void preHeaderUpstreamErrorIs502EntryShape() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepareFailure = new UpstreamException("cannot express upstream");
        var harness = new Harness(CHAT_BODY, fake);
        harness.dispatch();
        assertEquals(502, harness.status[0]);
        assertEquals(fake.errorJson, harness.body.toString(StandardCharsets.UTF_8));
        assertTrue(harness.upstream.requests.isEmpty());
    }

    @Test
    void unknownDegradationIdFailsPreHeaderWith400() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(CHAT_BODY, fake,
                Map.of("x-llmgw-allow-degradation", "bogus.id"));
        harness.dispatch();
        assertEquals(400, harness.status[0]);
        assertEquals(fake.errorJson, harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(0, fake.prepareCalls.get(), "the grant is validated before prepare");
        assertTrue(harness.upstream.requests.isEmpty());
    }

    @Test
    void degradationGrantIsParsedAndTheHeaderIsStrippedUpstream() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(CHAT_BODY, fake,
                Map.of("x-llmgw-allow-degradation", "thinking.display_raw"));
        harness.upstream.nonStreamBody = UPSTREAM_BODY;
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertTrue(fake.lastGrant.allows("thinking.display_raw"));
        assertEquals("tenant", fake.lastTenantId);
        assertEquals("tenant", fake.lastProjectId);
        UpstreamRequest sent = harness.upstream.last();
        assertTrue(sent.passHeaders().keySet().stream()
                        .noneMatch(name -> name.equalsIgnoreCase("x-llmgw-allow-degradation")),
                "the gateway-only degradation header must never reach the upstream");
    }

    @Test
    void degradationGrantHeaderIsCaseInsensitive() throws Exception {
        var fake = new FakeConversion(true);
        // RFC 7230 §3.2: field names are case-insensitive. The documented canonical spelling must
        // be honored exactly like the lowercase form, not silently ignored.
        var harness = new Harness(CHAT_BODY, fake,
                Map.of(DegradationGrant.HEADER, "thinking.display_raw"));
        harness.upstream.nonStreamBody = UPSTREAM_BODY;
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertTrue(fake.lastGrant.allows("thinking.display_raw"),
                "the canonical-case grant header must be parsed, not silently ignored");
        UpstreamRequest sent = harness.upstream.last();
        assertTrue(sent.passHeaders().keySet().stream()
                        .noneMatch(name -> name.equalsIgnoreCase(DegradationGrant.HEADER)),
                "the gateway-only degradation header must never reach the upstream");
    }

    @Test
    void passthroughIsByteForByteWhenTheServiceIsNull() throws Exception {
        var harness = new Harness(CHAT_BODY, null);
        harness.upstream.nonStreamBody = UPSTREAM_BODY;
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(UPSTREAM_BODY, harness.body.toString(StandardCharsets.UTF_8));
        assertTrue(harness.upstream.last().rawJson().contains("deepseek-chat"),
                "passthrough still rewrites the target model");
        assertEquals(1, harness.chain.cached.size());
        assertEquals(UPSTREAM_BODY, harness.chain.cached.get(0).body());
    }

    @Test
    void passthroughIsByteForByteWhenAppliesIsFalse() throws Exception {
        var fake = new FakeConversion(false);
        var harness = new Harness(CHAT_BODY, fake);
        harness.upstream.nonStreamBody = UPSTREAM_BODY;
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(UPSTREAM_BODY, harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(0, fake.prepareCalls.get());
        assertTrue(harness.upstream.last().rawJson().contains("deepseek-chat"));
    }

    @Test
    void cacheHitIsAlreadyEntryShapedAndNeverReTranscoded() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(CHAT_BODY, fake);
        harness.chain.cacheHit = Optional.of(ENTRY_BODY);
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(ENTRY_BODY, harness.body.toString(StandardCharsets.UTF_8));
        assertTrue(harness.upstream.requests.isEmpty(), "a cache hit never calls the upstream");
        assertEquals(0, fake.prepared.transcodeCalls.get());
        assertEquals(1, fake.prepared.closeCalls.get(), "the handle is closed even on a cache hit");
    }

    @Test
    void streamIllegalUpstreamChunkEmitsAnEntryError() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepared.transcoderFailure = new UpstreamException("illegal upstream chunk");
        fake.prepared.errorLines = List.of("data: {\"error\":{\"message\":\"illegal\",\"type\":\"server_error\"}}");
        fake.prepared.terminalAfterError = true;
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of(UPSTREAM_DELTA);
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals("data: {\"error\":{\"message\":\"illegal\",\"type\":\"server_error\"}}\n\n",
                harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(false), harness.chain.finished);
    }

    @Test
    void streamUpstreamErrorBeforeAnyDataIsPreHeader502EntryJson() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of();
        harness.upstream.streamFailure = new IllegalStateException("upstream refused");
        harness.dispatch();
        assertEquals(502, harness.status[0]);
        assertEquals(fake.errorJson, harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(false), harness.chain.finished);
    }

    @Test
    void streamEmptyUpstreamIsPreHeader502EntryJsonNeverAFakeSuccess() throws Exception {
        var fake = new FakeConversion(true);
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of();
        harness.dispatch();
        assertEquals(502, harness.status[0]);
        assertEquals(fake.errorJson, harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(false), harness.chain.finished);
    }

    @Test
    void streamHeartbeatPassesThroughVerbatimAndIsNeverTranscoded() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: [DONE]");
        fake.prepared.terminalAfterComplete = true;
        var harness = new Harness(STREAM_BODY, fake);
        harness.upstream.streamLines = List.of(": keep-alive\n\n", UPSTREAM_DELTA, "data: [DONE]\n");
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(": keep-alive\n\n" + ENTRY_DELTA + "\n\n" + "data: [DONE]\n\n",
                harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(true), harness.chain.finished);
    }

    @Test
    void streamPassthroughIsByteForByteWhenTheServiceIsNull() throws Exception {
        var harness = new Harness(STREAM_BODY, null);
        harness.upstream.streamLines = List.of(UPSTREAM_DELTA, "data: [DONE]\n");
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(UPSTREAM_DELTA + "data: [DONE]\n", harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(true), harness.chain.finished);
    }

    // ---------- controlled upstream ----------

    static final class ControlledUpstream implements UpstreamProvider {
        final List<UpstreamRequest> requests = new CopyOnWriteArrayList<>();
        volatile String nonStreamBody = UPSTREAM_BODY;
        volatile List<String> streamLines = List.of();
        volatile RuntimeException streamFailure;

        UpstreamRequest last() {
            return requests.get(requests.size() - 1);
        }

        public String id() {
            return "controlled";
        }

        public String completeRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            listener.onHeaders(200, Map.of());
            return nonStreamBody;
        }

        public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
            requests.add(request);
            sink.onHeaders(200, Map.of());
            for (String line : streamLines) {
                sink.onChunk(line);
            }
            if (streamFailure != null) {
                sink.onError(streamFailure);
                throw streamFailure;
            }
            sink.onDone();
        }

        public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    // ---------- fake conversion (conversion-api only) ----------

    static final class FakeConversion implements ConversionService {
        final boolean applies;
        final FakePrepared prepared = new FakePrepared();
        final AtomicInteger prepareCalls = new AtomicInteger();
        volatile RuntimeException prepareFailure;
        volatile DegradationGrant lastGrant;
        volatile String lastTenantId;
        volatile String lastProjectId;
        volatile String errorJson = "{\"error\":{\"message\":\"fake\",\"type\":\"invalid_request_error\"}}";

        FakeConversion(boolean applies) {
            this.applies = applies;
        }

        public boolean applies(Route route) {
            return applies;
        }

        public PreparedConversion prepare(ParsedRequest parsed, Route route, String tenantId,
                                          String projectId, DegradationGrant grant) {
            prepareCalls.incrementAndGet();
            lastGrant = grant;
            lastTenantId = tenantId;
            lastProjectId = projectId;
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            return prepared;
        }

        @Override
        public String errorJson(ProtocolKind kind, CapabilityException error) {
            return errorJson;
        }
    }

    static final class FakePrepared implements PreparedConversion {
        final AtomicInteger transcodeCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger transcoderCalls = new AtomicInteger();
        volatile String transcodedFrom;
        volatile String entryBody = ENTRY_BODY;
        volatile List<String> entryLines = List.of();
        volatile List<String> completeLines = List.of();
        volatile List<String> errorLines = List.of();
        volatile boolean terminalAfterComplete;
        volatile boolean terminalAfterError;
        volatile RuntimeException transcoderFailure;

        public String upstreamJson() {
            return PREPARED_JSON;
        }

        public String upstreamPath() {
            return "/chat/completions";
        }

        public String transcodeResponse(String upstreamJson) {
            transcodeCalls.incrementAndGet();
            transcodedFrom = upstreamJson;
            return entryBody;
        }

        public StreamTranscoder newStreamTranscoder() {
            transcoderCalls.incrementAndGet();
            return new FakeTranscoder(this);
        }

        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    static final class FakeTranscoder implements StreamTranscoder {
        private final FakePrepared owner;
        private volatile boolean terminal;

        FakeTranscoder(FakePrepared owner) {
            this.owner = owner;
        }

        public List<String> onUpstreamLine(String line) {
            if (owner.transcoderFailure != null) {
                throw owner.transcoderFailure;
            }
            List<String> out = new ArrayList<>(owner.entryLines);
            owner.entryLines = List.of();
            return out;
        }

        public List<String> onUpstreamComplete() {
            terminal |= owner.terminalAfterComplete;
            return owner.completeLines;
        }

        public List<String> onUpstreamError(Throwable error) {
            terminal |= owner.terminalAfterError;
            return owner.errorLines;
        }

        public boolean terminalSeen() {
            return terminal;
        }

        public void close() {
        }
    }

    // ---------- harness ----------

    static final class RecordingChain implements GovernanceChain {
        record Cached(String keyId, String body) {
        }

        final List<Cached> cached = new CopyOnWriteArrayList<>();
        final List<Boolean> finished = new CopyOnWriteArrayList<>();
        volatile Optional<String> cacheHit = Optional.empty();

        public GatewayDecision enter(String token, ParsedRequest request, Route target) {
            return GatewayDecision.allow("tenant", request, target);
        }

        public Optional<String> lookupCache(String key, ParsedRequest request) {
            return cacheHit;
        }

        public void cache(String key, ParsedRequest request, String body) {
            cached.add(new Cached(key, body));
        }

        public void finish(GatewayContext context, boolean success) {
            finished.add(success);
        }

        public Map<String, Object> snapshot() {
            return Map.of();
        }
    }

    static final class Harness {
        final int[] status = {0};
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final ControlledUpstream upstream = new ControlledUpstream();
        final RecordingChain chain = new RecordingChain();
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final VirtualThreadGatewayController controller;
        final HttpServletRequest request;
        final HttpServletResponse response;
        final Route route = new Route("test", "*", CALLER_MODEL, "deepseek-chat", "http://unused",
                "sk-test", "", 1, List.of(), "chat");

        Harness(String json, ConversionService conversion) {
            this(json, conversion, Map.of());
        }

        Harness(String json, ConversionService conversion, Map<String, String> extraHeaders) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer test-token");
            headers.put("Content-Type", "application/json");
            headers.putAll(extraHeaders);

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
            request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getInputStream" -> servletIn;
                        case "getHeader" -> headers.get((String) args[0]);
                        case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                        default -> defaultValue(method.getReturnType());
                    });

            var servletOut = new ServletOutputStream() {
                public void write(int b) {
                    body.write(b);
                }
                public boolean isReady() {
                    return true;
                }
                public void setWriteListener(WriteListener listener) {
                }
            };
            response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> {
                            status[0] = (Integer) args[0];
                            yield null;
                        }
                        case "getStatus" -> status[0];
                        case "getOutputStream" -> servletOut;
                        case "isCommitted" -> false;
                        default -> defaultValue(method.getReturnType());
                    });

            var router = new Router() {
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
            // Allow-all so the degradation-header strip is observable.
            var headerPolicy = new HeaderPolicy() {
                public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) {
                    Map<String, String> out = new LinkedHashMap<>(values);
                    out.remove("Authorization");
                    out.remove("Content-Type");
                    return out;
                }
                public List<String> toClient(Map<String, List<String>> values) {
                    return List.of();
                }
            };
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(10);
            properties.getUpstream().setHeartbeatIntervalMs(0);
            controller = new VirtualThreadGatewayController(chain, router, upstream,
                    CircuitBreaker.noop(), properties, headerPolicy, tasks, conversion);
        }

        void dispatch() throws IOException {
            controller.handleChat(request, response);
        }
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
}