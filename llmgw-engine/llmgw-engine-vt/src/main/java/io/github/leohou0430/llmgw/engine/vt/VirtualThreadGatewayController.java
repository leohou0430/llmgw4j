package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Blocking data plane on virtual threads (one request = one virtual thread).
 * Deliberately uses the same upstream client as the WebFlux engine so the benchmark
 * has exactly ONE variable: the concurrency model.
 *
 * <p>Streaming error contract: a stream counts as successful ONLY when the
 * upstream actually emitted {@code data: [DONE]}. A clean EOF without that
 * terminator is a silent truncation, not a success. Before the first chunk the
 * response is still uncommitted, so any failure yields a real HTTP 502; after
 * the first chunk the status is on the wire, so the failure is reported
 * in-band as an SSE {@code event: error} followed by {@code [DONE]}.
 * Fallback is only ever attempted before the first chunk. Once [DONE] is out,
 * a late upstream disconnect does not invalidate the completed answer.
 */
@RestController
@Profile(EngineProfiles.VT)
public class VirtualThreadGatewayController {

    private final GovernanceChain chain;
    private final Router router;
    private final UpstreamProvider provider;
    private final CircuitBreaker breaker;
    private final GatewayProperties properties;

    public VirtualThreadGatewayController(GovernanceChain chain, Router router,
                                          UpstreamProvider provider, CircuitBreaker breaker,
                                          GatewayProperties properties) {
        this.chain = chain;
        this.router = router;
        this.provider = provider;
        this.breaker = breaker;
        this.properties = properties;
    }

    @PostMapping("/v1/chat/completions")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        int cap = (int) Math.min(max, Integer.MAX_VALUE - 1) + 1;
        byte[] bytes = request.getInputStream().readNBytes(cap);
        if (bytes.length > max) {
            writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
            return;
        }
        String rawJson = new String(bytes, StandardCharsets.UTF_8);
        if (rawJson.isBlank()) {
            rawJson = "{}";
        }
        String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
        response.setHeader(EngineHeaders.REQUEST_ID, requestId);
        GatewayDecision decision = GatewayPipeline.enter(chain, router,
                request.getHeader("Authorization"), rawJson);
        if (!decision.allowed()) {
            writeError(response, decision);
            return;
        }
        ParsedRequest parsed = decision.parsed();
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        Route route = decision.route();
        int timeout = properties.getUpstream().getTimeoutMs();
        boolean success = false;
        try {
            if (parsed.stream()) {
                success = handleStream(response, rawJson, parsed, route, timeout, context);
            } else {
                Optional<String> cached = chain.lookupCache(decision.keyId(), parsed);
                String body;
                if (cached.isPresent()) {
                    context.markCached();
                    body = cached.get();
                } else {
                    body = completeWithFallback(rawJson, parsed, route, timeout, context);
                    context.markUpstreamEnd();
                }
                // Single parse: token count AND cacheability come from the
                // same tree (the body previously went through readTree twice).
                JsonNode root = ProtocolCodec.tryParse(body);
                context.setCompletionTokensIfAbsent(ProtocolCodec.completionTokensFromResponse(root));
                if (cached.isEmpty()) {
                    chain.cache(decision.keyId(), parsed, body,
                            ProtocolCodec.isCacheableResponse(root));
                }
                String upstream = EngineTimings.upstreamOrNull(context);
                if (upstream != null) {
                    response.setHeader(EngineHeaders.UPSTREAM_MS, upstream);
                }
                response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
                response.setStatus(200);
                response.setContentType(EngineHeaders.APPLICATION_JSON);
                response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
                success = true;
            }
        } catch (Exception e) {
            if (!response.isCommitted()) {
                writeError(response, GatewayDecision.deny(502,
                        EngineReasons.upstream(e)));
            }
        } finally {
            GatewayPipeline.finish(chain, context, success);
        }
    }

    /**
     * Do NOT call flushBuffer() before the first chunk: that commits the
     * response and turns a failed upstream into a misleading "200 OK, empty
     * body". Headers alone keep the response mutable until the first byte.
     */
    private boolean handleStream(HttpServletResponse response, String rawJson,
                                 ParsedRequest parsed, Route route, int timeout,
                                 GatewayContext context) {
        response.setStatus(200);
        response.setContentType(EngineHeaders.TEXT_EVENT_STREAM);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_CACHE);
        // Headers are still mutable here (no flushBuffer before the first
        // chunk). Both engines report only pre-commit work; full TTFT and
        // upstream timings are recorded as llmgw.ttft / llmgw.upstream meters.
        response.setHeader(EngineHeaders.OVERHEAD_MS,
                EngineTimings.preflight(context));

        StreamSink sink = new StreamSink(response, context);
        try {
            streamWithFallback(rawJson, parsed, route, timeout, sink, context);
            // A clean EOF is NOT proof of completion. Only an explicit upstream
            // [DONE] frame is. Do not fabricate one here.
            if (!sink.isComplete()) {
                return handleTruncated(response, sink);
            }
            return true;
        } catch (Exception e) {
            if (sink.isComplete()) {
                // Protocol terminator already delivered; a late disconnect is
                // not a failure and must not append a spurious error event.
                return true;
            }
            return handleFailure(response, sink,
                    EngineReasons.upstream(e));
        }
    }

    /** Shared terminal handling for thrown errors and silent truncation. */
    private boolean handleFailure(HttpServletResponse response, StreamSink sink, String reason) {
        if (sink.isComplete()) {
            return true;
        }
        if (!sink.chunkSent && !response.isCommitted()) {
            try {
                writeError(response, GatewayDecision.deny(502, reason));
            } catch (IOException ignored) {
                // Client disconnected; nothing left to report to.
            }
        } else {
            sink.writeErrorEvent(reason);
        }
        return false;
    }

    private boolean handleTruncated(HttpServletResponse response, StreamSink sink) {
        return handleFailure(response, sink, EngineReasons.truncated());
    }

    private void streamWithFallback(String rawJson, ParsedRequest parsed, Route primary,
                                    int timeout, StreamSink sink, GatewayContext context) {
        List<Route> candidates = EngineRoutes.candidates(router, primary);
        RuntimeException last = null;
        for (Route candidate : candidates) {
            if (!breaker.allow(candidate)) {
                // Fail fast locally, then move on to the next candidate.
                last = new CircuitOpenException(candidate.id());
                continue;
            }
            try {
                context.markUpstreamStart();
                provider.streamRawBlocking(EngineRoutes.request(rawJson, parsed, candidate, timeout), sink);
                breaker.recordSuccess(candidate);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (sink.chunkSent) {
                    // Status and headers are committed: never splice a second
                    // upstream's chunks into a stream the client already saw.
                    // (chunkSent can only belong to THIS candidate: a chunked
                    // failure of an earlier candidate already broke the loop.)
                    break;
                }
                breaker.recordFailure(candidate);
            }
        }
        throw last == null ? new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE) : last;
    }

    private String completeWithFallback(String rawJson, ParsedRequest parsed,
                                        Route primary, int timeout,
                                        GatewayContext context) {
        List<Route> candidates = EngineRoutes.candidates(router, primary);
        RuntimeException last = null;
        for (Route route : candidates) {
            if (!breaker.allow(route)) {
                last = new CircuitOpenException(route.id());
                continue;
            }
            try {
                context.markUpstreamStart();
                String body = provider.completeRaw(EngineRoutes.request(rawJson, parsed, route, timeout));
                breaker.recordSuccess(route);
                return body;
            } catch (RuntimeException e) {
                last = e;
                breaker.recordFailure(route);
            }
        }
        throw last == null ? new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE) : last;
    }

    private void writeError(HttpServletResponse response, GatewayDecision decision) throws IOException {
        response.setStatus(decision.status());
        response.setContentType(EngineHeaders.APPLICATION_JSON);
        response.getOutputStream().write(
                ProtocolCodec.errorJson(decision.reason()).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /** One request = one virtual thread, so plain booleans are sufficient. */
    private static final class StreamSink implements ChunkSink {

        private final HttpServletResponse response;
        private final GatewayContext context;
        private OutputStream out;
        private boolean chunkSent;
        private boolean doneWritten;
        private boolean errorWritten;

        StreamSink(HttpServletResponse response, GatewayContext context) {
            this.response = response;
            this.context = context;
        }

        @Override
        public void onChunk(String rawSseLine) {
            boolean done = SseChunkAccounting.apply(rawSseLine, context);
            if (!rawSseLine.isBlank() && !chunkSent) {
                context.markFirstChunk();
            }
            write(rawSseLine);
            if (!rawSseLine.isBlank()) {
                chunkSent = true;
            }
            if (done) {
                doneWritten = true;
            }
        }

        @Override
        public void onDone() {
            // Upstream reached a clean EOF. Intentionally do NOT synthesize a
            // [DONE] frame: that would disguise a truncated stream as a success.
            // handleStream() inspects isComplete() and reports the truncation.
        }

        /** True only when the upstream itself emitted the protocol terminator. */
        boolean isComplete() {
            return doneWritten;
        }

        @Override
        public void onError(Throwable error) {
            // JdkHttpUpstream rethrows after this callback; no need to throw here.
        }

        void writeErrorEvent(String reason) {
            if (errorWritten || doneWritten) {
                return;
            }
            errorWritten = true;
            try {
                write(ProtocolCodec.errorEvent(reason));
                if (!doneWritten) {
                    write(ProtocolCodec.doneLine());
                    doneWritten = true;
                }
            } catch (RuntimeException ignored) {
                // Best effort: the client may already be gone.
            }
        }

        private void write(String line) {
            try {
                if (out == null) {
                    out = response.getOutputStream();
                }
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
