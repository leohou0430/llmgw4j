package io.github.leohou0430.llmgw.engine.webflux;

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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T1.12 WebFlux seam against a CONTROLLED upstream (no network): the conversion handle is a fake
 * so this module keeps depending on conversion-api only. Real-codec round trips live in llmgw-app.
 */
class WebfluxConversionTest {

    static final String CALLER_MODEL = "client-model";
    static final String CHAT_BODY = "{\"model\":\"" + CALLER_MODEL
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String PREPARED_JSON = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String UPSTREAM_BODY = "{\"id\":\"up\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    static final String ENTRY_BODY = "{\"id\":\"entry\",\"model\":\"" + CALLER_MODEL + "\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    static final String STREAM_BODY = "{\"model\":\"" + CALLER_MODEL + "\",\"stream\":true,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String ENTRY_DELTA = "data: {\"id\":\"e\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}";
    static final String UPSTREAM_DELTA = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n";

    @org.junit.jupiter.api.Test
    void nonStreamConversionSendsThePreparedPayloadAndReturnsTheEntryBody() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.nonStreamBody = UPSTREAM_BODY;
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(ENTRY_BODY, response.getBodyAsString().block());
            assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());

            UpstreamRequest sent = fixture.upstream.last();
            assertEquals(PREPARED_JSON, sent.rawJson(), "the prepared payload reaches the wire verbatim");
            assertEquals("/chat/completions", sent.path());
            assertEquals(CALLER_MODEL, sent.model(), "no second target-model rewrite");
            assertEquals(1, fake.prepareCalls.get());
            assertEquals(1, fake.prepared.transcodeCalls.get());
            assertEquals(UPSTREAM_BODY, fake.prepared.transcodedFrom);
            assertEquals(1, fake.prepared.closeCalls.get(), "the handle must be closed");
            // The cache stores the ENTRY body keyed by the entry request.
            assertEquals(1, fixture.chain.cached.size());
            assertEquals(ENTRY_BODY, fixture.chain.cached.get(0).body());
        }
    }

    @org.junit.jupiter.api.Test
    void postWaitsForConversionCleanupAfterResponseCompletion() throws Exception {
        var fake = new FakeConversion(true);
        fake.prepared.closeRelease = new CountDownLatch(1);
        try (var fixture = new Fixture(fake); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = pool.submit(() -> fixture.post("/v1/chat/completions", CHAT_BODY, Map.of()));
            try {
                assertTrue(fake.prepared.closeEntered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> response.get(200, TimeUnit.MILLISECONDS),
                        "the fixture must await cleanup before exposing lifecycle assertions");
            } finally {
                fake.prepared.closeRelease.countDown();
            }
            assertEquals(HttpStatus.OK, response.get(5, TimeUnit.SECONDS).getStatusCode());
            assertEquals(1, fake.prepared.closeCalls.get());
        }
    }

    @org.junit.jupiter.api.Test
    void preHeaderCapabilityErrorUsesItsStatusAndEntryShape() {
        var fake = new FakeConversion(true);
        fake.prepareFailure = new CapabilityException(CapabilityException.INVALID_VALUE, 400, "model", "bad");
        try (var fixture = new Fixture(fake)) {
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals(fake.errorJson, response.getBodyAsString().block());
            assertTrue(fixture.upstream.requests.isEmpty(), "no upstream call on a pre-header failure");
            assertEquals(List.of(false), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void preHeaderUpstreamErrorIs502() {
        var fake = new FakeConversion(true);
        fake.prepareFailure = new UpstreamException("cannot express upstream");
        try (var fixture = new Fixture(fake)) {
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
            assertEquals(fake.errorJson, response.getBodyAsString().block(),
                    "an upstream failure renders the entry error shape with its own 502");
            assertTrue(fixture.upstream.requests.isEmpty());
        }
    }

    @org.junit.jupiter.api.Test
    void unknownDegradationIdFailsPreHeaderWith400() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            var response = fixture.post("/v1/chat/completions", CHAT_BODY,
                    Map.of("x-llmgw-allow-degradation", "bogus.id"));
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals(fake.errorJson, response.getBodyAsString().block());
            assertEquals(0, fake.prepareCalls.get(), "the grant is validated before prepare");
            assertTrue(fixture.upstream.requests.isEmpty());
        }
    }

    @org.junit.jupiter.api.Test
    void degradationGrantIsParsedAndTheHeaderIsStrippedUpstream() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.nonStreamBody = UPSTREAM_BODY;
            var response = fixture.post("/v1/chat/completions", CHAT_BODY,
                    Map.of("x-llmgw-allow-degradation", "thinking.display_raw"));
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(fake.lastGrant.allows("thinking.display_raw"));
            assertEquals("tenant", fake.lastTenantId);
            assertEquals("tenant", fake.lastProjectId);
            UpstreamRequest sent = fixture.upstream.last();
            assertTrue(sent.passHeaders().keySet().stream()
                            .noneMatch(name -> name.equalsIgnoreCase("x-llmgw-allow-degradation")),
                    "the gateway-only degradation header must never reach the upstream");
        }
    }

    @org.junit.jupiter.api.Test
    void degradationGrantHeaderIsCaseInsensitive() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.nonStreamBody = UPSTREAM_BODY;
            // RFC 7230 §3.2: field names are case-insensitive. A client sending the documented
            // canonical spelling must be honored exactly like the lowercase form, not ignored.
            var response = fixture.post("/v1/chat/completions", CHAT_BODY,
                    Map.of(DegradationGrant.HEADER, "thinking.display_raw"));
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(fake.lastGrant.allows("thinking.display_raw"),
                    "the canonical-case grant header must be parsed, not silently ignored");
            UpstreamRequest sent = fixture.upstream.last();
            assertTrue(sent.passHeaders().keySet().stream()
                            .noneMatch(name -> name.equalsIgnoreCase(DegradationGrant.HEADER)),
                    "the gateway-only degradation header must never reach the upstream");
        }
    }

    @org.junit.jupiter.api.Test
    void passthroughIsByteForByteWhenTheServiceIsNull() {
        try (var fixture = new Fixture(null)) {
            fixture.upstream.nonStreamBody = UPSTREAM_BODY;
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(UPSTREAM_BODY, response.getBodyAsString().block());
            UpstreamRequest sent = fixture.upstream.last();
            assertTrue(sent.rawJson().contains("deepseek-chat"), "passthrough still rewrites the target model");
            assertEquals(1, fixture.chain.cached.size());
            assertEquals(UPSTREAM_BODY, fixture.chain.cached.get(0).body());
        }
    }

    @org.junit.jupiter.api.Test
    void passthroughIsByteForByteWhenAppliesIsFalse() {
        var fake = new FakeConversion(false);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.nonStreamBody = UPSTREAM_BODY;
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(UPSTREAM_BODY, response.getBodyAsString().block());
            assertEquals(0, fake.prepareCalls.get());
            assertTrue(fixture.upstream.last().rawJson().contains("deepseek-chat"));
        }
    }

    @org.junit.jupiter.api.Test
    void cacheHitIsAlreadyEntryShapedAndNeverReTranscoded() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.chain.cacheHit = Optional.of(ENTRY_BODY);
            var response = fixture.post("/v1/chat/completions", CHAT_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(ENTRY_BODY, response.getBodyAsString().block());
            assertTrue(fixture.upstream.requests.isEmpty(), "a cache hit never calls the upstream");
            assertEquals(0, fake.prepared.transcodeCalls.get());
            assertEquals(1, fake.prepared.closeCalls.get(), "the handle is closed even on a cache hit");
        }
    }

    @org.junit.jupiter.api.Test
    void streamConversionTranscodesUpstreamLinesAndEmitsExactlyOneEntryTerminal() {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: [DONE]");
        fake.prepared.terminalAfterComplete = true;
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of(UPSTREAM_DELTA, "data: [DONE]\n");
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
            String body = response.getBodyAsString().block();
            assertEquals(ENTRY_DELTA + "\n\n" + "data: [DONE]\n\n", body);
            UpstreamRequest sent = fixture.upstream.last();
            assertEquals(PREPARED_JSON, sent.rawJson());
            assertEquals("/chat/completions", sent.path());
            assertEquals(CALLER_MODEL, sent.model());
            assertTrue(sent.stream(), "the upstream request keeps the caller's stream flag");
            assertEquals(1, fake.prepared.transcoderCalls.get());
            assertEquals(1, fake.prepared.closeCalls.get());
            assertEquals(List.of(true), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void streamTruncationEmitsAnEntryErrorNeverAFakeSuccessOrADoubleError() {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: {\"error\":{\"message\":\"truncated\",\"type\":\"server_error\"}}");
        fake.prepared.terminalAfterComplete = true;
        try (var fixture = new Fixture(fake)) {
            // Upstream ends WITHOUT its terminator: a silent truncation.
            fixture.upstream.streamLines = List.of(UPSTREAM_DELTA);
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            String body = response.getBodyAsString().block();
            assertEquals(ENTRY_DELTA + "\n\n"
                    + "data: {\"error\":{\"message\":\"truncated\",\"type\":\"server_error\"}}\n\n", body);
            assertTrue(!body.contains("[DONE]"), "a truncation must never fabricate a success terminal");
            assertEquals(1, countOccurrences(body, "\"error\""), "exactly one entry error, no double error");
            assertEquals(List.of(false), fixture.chain.finished, "a truncation is billed as a failure");
            assertEquals(1, fake.prepared.closeCalls.get());
        }
    }

    static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @org.junit.jupiter.api.Test
    void streamIllegalUpstreamChunkEmitsAnEntryError() {
        var fake = new FakeConversion(true);
        fake.prepared.transcoderFailure = new UpstreamException("illegal upstream chunk");
        fake.prepared.errorLines = List.of("data: {\"error\":{\"message\":\"illegal\",\"type\":\"server_error\"}}");
        fake.prepared.terminalAfterError = true;
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of(UPSTREAM_DELTA);
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            String body = response.getBodyAsString().block();
            assertEquals("data: {\"error\":{\"message\":\"illegal\",\"type\":\"server_error\"}}\n\n", body);
            assertEquals(List.of(false), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void streamUpstreamErrorBeforeAnyDataIsPreHeader502EntryJson() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of();
            fixture.upstream.streamFailure = new IllegalStateException("upstream refused");
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
            assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
            assertEquals(fake.errorJson, response.getBodyAsString().block());
            assertEquals(List.of(false), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void streamEmptyUpstreamIsPreHeader502EntryJsonNeverAFakeSuccess() {
        var fake = new FakeConversion(true);
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of();
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
            assertEquals(fake.errorJson, response.getBodyAsString().block());
            assertEquals(List.of(false), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void streamHeartbeatPassesThroughVerbatimAndIsNeverTranscoded() {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: [DONE]");
        fake.prepared.terminalAfterComplete = true;
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of(": keep-alive\n\n", UPSTREAM_DELTA, "data: [DONE]\n");
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            String body = response.getBodyAsString().block();
            assertEquals(": keep-alive\n\n" + ENTRY_DELTA + "\n\n" + "data: [DONE]\n\n", body);
            assertEquals(List.of(true), fixture.chain.finished);
        }
    }

    @org.junit.jupiter.api.Test
    void streamNormalCompletionDoesNotCancelTheUpstreamTransport() {
        var fake = new FakeConversion(true);
        fake.prepared.entryLines = List.of(ENTRY_DELTA);
        fake.prepared.completeLines = List.of("data: [DONE]");
        fake.prepared.terminalAfterComplete = true;
        try (var fixture = new Fixture(fake)) {
            fixture.upstream.streamLines = List.of(UPSTREAM_DELTA, "data: [DONE]\n");
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(0, fixture.upstream.cancels.get(),
                    "a normal completion must not cancel the upstream transport");
        }
    }

    @org.junit.jupiter.api.Test
    void streamPassthroughIsByteForByteWhenTheServiceIsNull() {
        try (var fixture = new Fixture(null)) {
            fixture.upstream.streamLines = List.of(UPSTREAM_DELTA, "data: [DONE]\n");
            var response = fixture.post("/v1/chat/completions", STREAM_BODY, Map.of());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(UPSTREAM_DELTA + "data: [DONE]\n", response.getBodyAsString().block());
            assertEquals(List.of(true), fixture.chain.finished);
        }
    }

    // ---------- controlled upstream ----------

    static final class ControlledUpstream implements UpstreamProvider {
        final List<UpstreamRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger cancels = new AtomicInteger();
        volatile String nonStreamBody = UPSTREAM_BODY;
        volatile RuntimeException nonStreamFailure;
        volatile List<String> streamLines = List.of();
        volatile RuntimeException streamFailure;
        /** When true the publisher stays open until cancelled (client-disconnect probe). */
        volatile boolean holdOpen;

        UpstreamRequest last() {
            return requests.get(requests.size() - 1);
        }

        public String id() {
            return "controlled";
        }

        public String completeRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            listener.onHeaders(200, Map.of());
            if (nonStreamFailure != null) {
                throw nonStreamFailure;
            }
            return nonStreamBody;
        }

        public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
            throw new UnsupportedOperationException();
        }

        public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
            requests.add(request);
            List<String> lines = streamLines;
            RuntimeException failure = streamFailure;
            boolean hold = holdOpen;
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicInteger index = new AtomicInteger();
                private volatile boolean cancelled;

                public void request(long n) {
                    if (cancelled) {
                        return;
                    }
                    listener.onHeaders(200, Map.of());
                    if (hold) {
                        return;
                    }
                    for (long i = 0; i < n && !cancelled; i++) {
                        int at = index.getAndIncrement();
                        if (at >= lines.size()) {
                            if (failure != null) {
                                subscriber.onError(failure);
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
                    cancels.incrementAndGet();
                }
            });
        }
    }

    // ---------- fake conversion (conversion-api only) ----------

    static final class FakeConversion implements ConversionService {
        final boolean applies;
        final FakePrepared prepared = new FakePrepared();
        final AtomicInteger prepareCalls = new AtomicInteger();
        volatile RuntimeException prepareFailure;
        volatile RuntimeException prepareUpstreamFailure;
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
            if (prepareUpstreamFailure != null) {
                throw prepareUpstreamFailure;
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
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CountDownLatch closeRelease;
        final AtomicInteger transcoderCalls = new AtomicInteger();
        volatile String transcodedFrom;
        volatile String entryBody = ENTRY_BODY;
        volatile RuntimeException transcodeFailure;
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
            if (transcodeFailure != null) {
                throw transcodeFailure;
            }
            return entryBody;
        }

        public StreamTranscoder newStreamTranscoder() {
            transcoderCalls.incrementAndGet();
            return new FakeTranscoder(this);
        }

        public void close() {
            closeEntered.countDown();
            if (closeRelease != null) {
                try {
                    assertTrue(closeRelease.await(5, TimeUnit.SECONDS), "cleanup probe released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
            closeCalls.incrementAndGet();
            closed.countDown();
        }
    }

    static final class FakeTranscoder implements StreamTranscoder {
        private final FakePrepared owner;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile boolean terminal;

        FakeTranscoder(FakePrepared owner) {
            this.owner = owner;
        }

        public List<String> onUpstreamLine(String line) {
            if (owner.transcoderFailure != null) {
                throw owner.transcoderFailure;
            }
            List<String> out = new ArrayList<>();
            for (String entry : owner.entryLines) {
                out.add(entry);
            }
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
            closeCalls.incrementAndGet();
        }
    }

    // ---------- harness ----------

    static final class RecordingChain implements GovernanceChain {
        final CountDownLatch finalized = new CountDownLatch(1);
        record Cached(String keyId, String body) {
        }

        final List<Cached> cached = new CopyOnWriteArrayList<>();
        final List<Boolean> finished = new CopyOnWriteArrayList<>();
        volatile Optional<String> cacheHit = Optional.empty();

        public GatewayDecision enter(String token, ParsedRequest request, Route target) {
            return GatewayDecision.allow("tenant", request, target).withScope("tenant", "tenant");
        }

        public Optional<String> lookupCache(String key, ParsedRequest request) {
            return cacheHit;
        }

        public void cache(String key, ParsedRequest request, String body) {
            cached.add(new Cached(key, body));
        }

        public void finish(GatewayContext context, boolean success) {
            finished.add(success);
            finalized.countDown();
        }

        public Map<String, Object> snapshot() {
            return Map.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WebConfig extends WebFluxConfigurationSupport {
    }

    static final class Fixture implements AutoCloseable {
        final ControlledUpstream upstream = new ControlledUpstream();
        final RecordingChain chain = new RecordingChain();
        final ConversionService conversion;
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final HttpHandler handler;
        final Route route = new Route("test", "*", CALLER_MODEL, "deepseek-chat", "http://unused",
                "sk-test", "", 1, List.of(), "chat");

        Fixture(ConversionService conversion) {
            this.conversion = conversion;
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(10);
            properties.getUpstream().setHeartbeatIntervalMs(0);
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
            // Allow-all so the degradation-header strip is observable (the real default is a whitelist).
            var headers = new HeaderPolicy() {
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
            context.getEnvironment().setActiveProfiles("webflux");
            context.registerBean(GatewayProperties.class, () -> properties);
            context.register(WebConfig.class, WebfluxEngineConfiguration.class);
            context.registerBean(WebfluxGatewayController.class, () -> new WebfluxGatewayController(
                    chain, router, upstream, CircuitBreaker.noop(), properties, headers, tasks, conversion));
            context.refresh();
            handler = WebHttpHandlerBuilder.applicationContext(context).build();
        }

        MockServerHttpResponse post(String path, String body, Map<String, String> headers) {
            var request = MockServerHttpRequest.post(path).contentType(MediaType.APPLICATION_JSON);
            headers.forEach(request::header);
            var response = new MockServerHttpResponse();
            handler.handle(request.body(body), response).block(Duration.ofSeconds(5));
            // Reactor propagates completion before doFinally. Await observable cleanup,
            // rather than racing the worker from lifecycle assertions on this thread.
            await(chain.finalized, "request settlement");
            if (conversion instanceof FakeConversion fake && fake.prepareCalls.get() > 0
                    && fake.prepareFailure == null && fake.prepareUpstreamFailure == null) {
                await(fake.prepared.closed, "conversion handle cleanup");
            }
            return response;
        }

        private static void await(CountDownLatch event, String description) {
            try {
                assertTrue(event.await(5, TimeUnit.SECONDS), description + " must complete");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }

        public void close() {
            context.close();
        }
    }

    static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle), () -> "expected [" + needle + "] in " + haystack);
    }

    static Flux<String> unused() {
        return Flux.empty();
    }
}
