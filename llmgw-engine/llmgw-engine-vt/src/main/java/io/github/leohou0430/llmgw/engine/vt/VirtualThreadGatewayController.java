package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.protocol.Protocols;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.core.upstream.UpstreamProvider;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.ForwardingPolicy;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blocking data plane on virtual threads (one request = one virtual thread).
 * Deliberately uses the same upstream client as the WebFlux engine so the benchmark
 * has exactly ONE variable: the concurrency model.
 *
 * <p>v12 multi-protocol: every POST endpoint (chat / responses / messages /
 * images / video-create) shares one handler parameterized by {@link ProtocolKind};
 * the kinds differ only in request parsing, SSE terminator and response-side
 * token/cache semantics, all of which hang off ParsedRequest.kind().
 *
 * <p>Streaming error contract: a stream counts as successful ONLY when the
 * upstream actually emitted its protocol terminator ([DONE] / response.completed
 * / message_stop). A clean EOF without that terminator is a silent truncation,
 * not a success. Before the first chunk the response is still uncommitted, so
 * any failure yields a real HTTP 502; after the first chunk the status is on
 * the wire, so the failure is reported in-band as an SSE {@code event: error}
 * followed by {@code [DONE]}. Fallback is only ever attempted before the first
 * chunk. Once the terminator is out, a late upstream disconnect does not
 * invalidate the completed answer.
 *
 * <p>Video tasks are asynchronous upstream jobs: create responses bind the task
 * id to the winning route ({@link TaskRouteSticky}), and poll/content GETs are
 * pinned to that route with NO fallback and NO circuit breaking — a task exists
 * only on the upstream that created it. A STICKY MISS (unknown task id) resolves
 * the wildcard route and DOES honor the circuit breaker (v13).
 *
 * <p>v13 forwarding: attempt ordering (same-route retry with backoff, then the
 * fallback chain) is delegated to {@link ForwardingPolicy}; header pass-through
 * in both directions is delegated to {@link HeaderPolicy}. Comment lines
 * (": ...", SSE heartbeat) are transport-level and excluded from accounting,
 * TTFT and the first-chunk guard. The provider's heartbeat watchdog may write
 * comment frames from another thread, so StreamSink writes are synchronized.
 */
@RestController
@Profile(EngineProfiles.VT)
public class VirtualThreadGatewayController {

    private final GovernanceChain chain;
    private final Router router;
    private final UpstreamProvider provider;
    private final CircuitBreaker breaker;
    private final GatewayProperties properties;
    /** W3.6: sticky store is an injectable SPI (in-memory default, Redis pack). */
    private final TaskRouteSticky taskRoutes;
    private final java.util.concurrent.Semaphore requestSlots;
    private final HeaderPolicy headerPolicy;
    private final ForwardingPolicy.RetryConfig retryConfig;

    public VirtualThreadGatewayController(GovernanceChain chain, Router router,
                                          UpstreamProvider provider, CircuitBreaker breaker,
                                          GatewayProperties properties, HeaderPolicy headerPolicy,
                                          TaskRouteSticky taskRoutes) {
        this.chain = chain;
        this.router = router;
        this.provider = provider;
        this.breaker = breaker;
        this.properties = properties;
        this.requestSlots = new java.util.concurrent.Semaphore(properties.getMaxConcurrentRequests());
        this.taskRoutes = taskRoutes;
        this.headerPolicy = headerPolicy;
        GatewayProperties.Upstream.Retry retry = properties.getUpstream().getRetry();
        this.retryConfig = new ForwardingPolicy.RetryConfig(
                retry.getAttempts(), retry.getBackoffBaseMs(), retry.getBackoffMaxMs());
    }

    @PostMapping("/v1/chat/completions")
    public void handleChat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.CHAT, request, response);
    }

    @PostMapping("/v1/responses")
    public void handleResponses(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.RESPONSES, request, response);
    }

    @PostMapping("/v1/messages")
    public void handleMessages(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.MESSAGES, request, response);
    }

    @PostMapping("/v1/images/generations")
    public void handleImages(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.IMAGES, request, response);
    }

    @PostMapping("/v1/videos")
    public void handleVideoCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.VIDEOS, request, response);
    }

    // W4.3 protocol-surface expansion: JSON-in/JSON-out pass-through kinds.
    @PostMapping("/v1/embeddings")
    public void handleEmbeddings(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.EMBEDDINGS, request, response);
    }

    @PostMapping("/v1/rerank")
    public void handleRerank(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.RERANK, request, response);
    }

    /** JSON in, BINARY out: same byte-faithful path as video content. */
    @PostMapping("/v1/audio/speech")
    public void handleSpeech(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleBinaryKind(ProtocolKind.SPEECH, "audio/mpeg", request, response);
    }

    /**
     * W4.3 binary-response kind (speech): full governance, then a byte-for-byte
     * pass-through. The bytes never round-trip a String.
     */
    private void handleBinaryKind(ProtocolKind kind, String contentType,
                                  HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requestSlots.tryAcquire()) {
            writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            return;
        }
        try {
            handleBinaryKindWithinCapacity(kind, contentType, request, response);
        } finally {
            requestSlots.release();
        }
    }

    private void handleBinaryKindWithinCapacity(ProtocolKind kind, String contentType,
                                  HttpServletRequest request, HttpServletResponse response) throws IOException {
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
        Map<String, String> clientHeaders = firstValues(request);
        String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
        response.setHeader(EngineHeaders.REQUEST_ID, requestId);
        GatewayDecision decision = GatewayPipeline.enter(chain, router, kind,
                request.getHeader("Authorization"), rawJson);
        if (!decision.allowed()) {
            writeError(response, decision);
            return;
        }
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        Route route = decision.route();
        Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
        boolean success = false;
        try {
            if (!breaker.allow(route)) {
                throw new CircuitOpenException(route.id());
            }
            context.markUpstreamStart();
            byte[] body = provider.fetchRaw(EngineRoutes.postBytes(rawJson, decision.parsed(),
                    route, properties.getUpstream().getTimeoutMs(), pass));
            breaker.recordSuccess(route);
            context.markUpstreamEnd();
            response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
            response.setStatus(200);
            response.setContentType(contentType);
            response.getOutputStream().write(body);
            response.flushBuffer();
            success = true;
        } catch (Exception e) {
            if (!response.isCommitted()) {
                writeError(response, GatewayDecision.deny(502, EngineReasons.upstream(e)));
            }
        } finally {
            GatewayPipeline.finish(chain, context, success);
        }
    }

    @GetMapping("/v1/videos/{id}")
    public void handleVideoPoll(@PathVariable("id") String id,
                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id, EngineHeaders.APPLICATION_JSON,
                id, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/videos/{id}/content")
    public void handleVideoContent(@PathVariable("id") String id,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id + "/content", "video/mp4",
                id, UpstreamMethod.GET, request, response);
    }

    // W4.3 completion of the market parity list: uploads, files, batches.
    @PostMapping("/v1/audio/transcriptions")
    public void handleTranscriptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleUpload(ProtocolKind.TRANSCRIPTIONS, request, response);
    }

    @PostMapping("/v1/files")
    public void handleFileUpload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleUpload(ProtocolKind.FILES, request, response);
    }

    @GetMapping("/v1/files")
    public void handleFileList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.FILES, "/files", EngineHeaders.APPLICATION_JSON,
                null, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/files/{id}")
    public void handleFileGet(@PathVariable("id") String id,
                              HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON,
                id, UpstreamMethod.GET, request, response);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/v1/files/{id}")
    public void handleFileDelete(@PathVariable("id") String id,
                                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON,
                id, UpstreamMethod.DELETE, request, response);
    }

    @PostMapping("/v1/batches")
    public void handleBatchCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleKind(ProtocolKind.BATCHES, request, response);
    }

    @GetMapping("/v1/batches")
    public void handleBatchList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.BATCHES, "/batches", EngineHeaders.APPLICATION_JSON,
                null, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/batches/{id}")
    public void handleBatchPoll(@PathVariable("id") String id,
                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id, EngineHeaders.APPLICATION_JSON,
                id, UpstreamMethod.GET, request, response);
    }

    // B11: market-parity pass-through endpoints (models catalog, batch cancel,
    // file content download).
    @GetMapping("/v1/models")
    public void handleModels(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.CHAT, "/models", EngineHeaders.APPLICATION_JSON,
                null, UpstreamMethod.GET, request, response);
    }

    @PostMapping("/v1/batches/{id}/cancel")
    public void handleBatchCancel(@PathVariable("id") String id,
                                  HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id + "/cancel",
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.POST, request, response);
    }

    @GetMapping("/v1/files/{id}/content")
    public void handleFileContent(@PathVariable("id") String id,
                                  HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleStickyFetch(ProtocolKind.FILES, "/files/" + id + "/content",
                "application/octet-stream", id, UpstreamMethod.GET, request, response);
    }

    private void handleKind(ProtocolKind kind, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        if (!requestSlots.tryAcquire()) {
            writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            return;
        }
        try {
            handleKindWithinCapacity(kind, request, response);
        } finally {
            requestSlots.release();
        }
    }

    private void handleKindWithinCapacity(ProtocolKind kind, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
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
        Map<String, String> clientHeaders = firstValues(request);
        String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
        response.setHeader(EngineHeaders.REQUEST_ID, requestId);
        GatewayDecision decision = GatewayPipeline.enter(chain, router, kind,
                request.getHeader("Authorization"), rawJson);
        if (!decision.allowed()) {
            writeError(response, decision);
            return;
        }
        ParsedRequest parsed = decision.parsed();
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        if (!EngineRoutes.ownsReferencedFiles(parsed, decision.keyId(), decision.route(), taskRoutes)) {
            GatewayPipeline.finish(chain, context, false);
            writeError(response, GatewayDecision.deny(404, "referenced file not found on this route"));
            return;
        }
        Route route = decision.route();
        int timeout = properties.getUpstream().getTimeoutMs();
        ForwardingPolicy.RetryConfig retry = retryConfig;
        Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
        HeaderListener egress = egressListener(response);
        boolean success = false;
        try {
            if (parsed.stream()) {
                success = handleStream(kind, response, rawJson, parsed, route, timeout, context, pass);
            } else {
                boolean cacheBypass = "1".equals(clientHeaders.get("x-llmgw-no-cache"));
                Optional<String> cached = cacheBypass
                        ? Optional.empty()
                        : chain.lookupCache(decision.keyId(), parsed);
                String body;
                Route winning = route;
                if (cached.isPresent()) {
                    context.markCached();
                    body = cached.get();
                } else {
                    Completed completed = completeWithPlan(
                            EngineRoutes.forwardPlan(router, route, retry, parsed), 0,
                            rawJson, parsed, timeout, context, pass, egress, 0);
                    body = completed.body();
                    winning = completed.route();
                    context.markUpstreamEnd();
                }
                // Single parse: token count AND cacheability come from the
                // same tree (the body previously went through readTree twice).
                JsonNode root = ProtocolCodec.tryParse(body);
                context.setCompletionTokensIfAbsent(
                        ProtocolCodec.completionTokensFromResponse(kind, root));
                if (cached.isEmpty() && !cacheBypass) {
                    chain.cache(decision.keyId(), parsed, body,
                            ProtocolCodec.isCacheableResponse(kind, root));
                }
                // A video job lives on the upstream that created it: pin the
                // task id (async-job protocols only) to the route that
                // actually served the create call.
                String taskId = cached.isEmpty() ? Protocols.of(kind).taskId(root) : "";
                if (!taskId.isEmpty()) {
                    taskRoutes.bind(decision.keyId(), kind.wireName(), taskId, winning.id());
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
     * W4.3 byte-body upload kinds (transcriptions, files): full governance via
     * the byte-probe, then a byte-faithful POST. The multipart Content-Type
     * (with its boundary) rides the policy pass-through untouched.
     */
    private void handleUpload(ProtocolKind kind, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        if (!requestSlots.tryAcquire()) {
            writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            return;
        }
        try {
            handleUploadWithinCapacity(kind, request, response);
        } finally {
            requestSlots.release();
        }
    }

    private void handleUploadWithinCapacity(ProtocolKind kind, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        int cap = (int) Math.min(max, Integer.MAX_VALUE - 1) + 1;
        byte[] body = request.getInputStream().readNBytes(cap);
        if (body.length > max) {
            writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
            return;
        }
        Map<String, String> clientHeaders = firstValues(request);
        String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
        response.setHeader(EngineHeaders.REQUEST_ID, requestId);
        ParsedRequest parsed;
        try {
            parsed = Protocols.of(kind).parse(body);
        } catch (RuntimeException e) {
            writeError(response, GatewayDecision.deny(400,
                    "invalid multipart body: " + e.getMessage()));
            return;
        }
        GatewayDecision decision = chain.enter(request.getHeader("Authorization"), parsed,
                router.resolve(kind, parsed.model()));
        if (!decision.allowed()) {
            writeError(response, decision);
            return;
        }
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
        boolean success = false;
        try {
            if (!breaker.allow(decision.route())) {
                throw new CircuitOpenException(decision.route().id());
            }
            context.markUpstreamStart();
            byte[] upstreamBody = provider.fetchRaw(EngineRoutes.postRawBytes(body, parsed,
                    decision.route(), properties.getUpstream().getTimeoutMs(), pass));
            breaker.recordSuccess(decision.route());
            context.markUpstreamEnd();
            if (kind == ProtocolKind.FILES) {
                String resourceId = ProtocolCodec.tryParse(new String(upstreamBody, StandardCharsets.UTF_8))
                        .path("id").asText("");
                if (!resourceId.isBlank()) {
                    taskRoutes.bind(decision.keyId(), kind.wireName(), resourceId, decision.route().id());
                }
            }
            response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
            response.setStatus(200);
            response.setContentType(EngineHeaders.APPLICATION_JSON);
            response.getOutputStream().write(upstreamBody);
            response.flushBuffer();
            success = true;
        } catch (Exception e) {
            if (!response.isCommitted()) {
                writeError(response, GatewayDecision.deny(502, EngineReasons.upstream(e)));
            }
        } finally {
            GatewayPipeline.finish(chain, context, success);
        }
    }

    /**
     * Task poll / fetch GET/DELETE (video, batch, files): governance + sticky routing, then a raw
     * byte-for-byte pass-through. A PINNED task has no fallback and no circuit
     * breaking -- the task exists only on the upstream that created it, and a
     * create-route circuit must never strand a live job's polling. A STICKY
     * MISS resolves the wildcard route, which is an ordinary route call and
     * DOES honor the circuit breaker (v13).
     */
    private void handleStickyFetch(ProtocolKind kind, String upstreamPath, String contentType,
                                   String taskId, UpstreamMethod method,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requestSlots.tryAcquire()) {
            writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            return;
        }
        try {
            handleStickyFetchWithinCapacity(kind, upstreamPath, contentType, taskId, method, request, response);
        } finally {
            requestSlots.release();
        }
    }

    private void handleStickyFetchWithinCapacity(ProtocolKind kind, String upstreamPath, String contentType,
                                   String taskId, UpstreamMethod method,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        long arrived = System.nanoTime();
        String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
        response.setHeader(EngineHeaders.REQUEST_ID, requestId);
        ParsedRequest parsed = ParsedRequest.bodyless(kind);
        GatewayDecision decision = chain.enter(request.getHeader("Authorization"), parsed,
                router.resolve(kind, "*"));
        if (!decision.allowed()) {
            writeError(response, decision);
            return;
        }
        Route pinned = taskId == null ? null
                : router.byId(taskRoutes.resolve(decision.keyId(), kind.wireName(), taskId));
        if ((taskId != null && pinned == null)
                || (taskId == null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES))) {
            GatewayPipeline.finish(chain, GatewayPipeline.context(decision, requestId, arrived), false);
            writeError(response, GatewayDecision.deny(taskId == null ? 501 : 404,
                    taskId == null ? "tenant-scoped resource listing is unavailable" : "resource not found"));
            return;
        }
        boolean isPinned = pinned != null;
        Route route = isPinned ? pinned : decision.route();
        decision = decision.withRoute(route);
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        boolean success = false;
        boolean fetched = false;
        try {
            if (!isPinned && !breaker.allow(route)) {
                throw new CircuitOpenException(route.id());
            }
            context.markUpstreamStart();
            byte[] body = provider.fetchRaw(EngineRoutes.methodFetch(route, upstreamPath,
                    method, properties.getUpstream().getTimeoutMs()));
            if (!isPinned) {
                breaker.recordSuccess(route);
            }
            fetched = true;
            context.markUpstreamEnd();
            String upstream = EngineTimings.upstreamOrNull(context);
            if (upstream != null) {
                response.setHeader(EngineHeaders.UPSTREAM_MS, upstream);
            }
            response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
            response.setStatus(200);
            response.setContentType(contentType);
            response.getOutputStream().write(body);
            response.flushBuffer();
            success = true;
        } catch (Exception e) {
            if (!isPinned && !fetched && !(e instanceof CircuitOpenException)) {
                breaker.recordFailure(route);
            }
            if (!response.isCommitted()) {
                writeError(response, GatewayDecision.deny(502, EngineReasons.upstream(e)));
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
    private boolean handleStream(ProtocolKind kind, HttpServletResponse response, String rawJson,
                                 ParsedRequest parsed, Route route, int timeout,
                                 GatewayContext context, Map<String, String> pass) {
        response.setStatus(200);
        response.setContentType(EngineHeaders.TEXT_EVENT_STREAM);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_CACHE);
        // Headers are still mutable here (no flushBuffer before the first
        // chunk). Both engines report only pre-commit work; full TTFT and
        // upstream timings are recorded as llmgw.ttft / llmgw.upstream meters.
        response.setHeader(EngineHeaders.OVERHEAD_MS,
                EngineTimings.preflight(context));

        StreamSink sink = new StreamSink(response, context, headerPolicy);
        try {
            streamWithPlan(EngineRoutes.forwardPlan(router, route, retryConfig, parsed), 0,
                    rawJson, parsed, timeout, sink, context, pass, 0);
            // A clean EOF is NOT proof of completion. Only an explicit upstream
            // terminator frame is. Do not fabricate one here.
            if (!sink.isComplete()) {
                return handleFailure(response, sink, EngineReasons.truncated(kind));
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

    /**
     * The attempt loop, driven by the shared {@link ForwardingPolicy} plan:
     * same-route retries honor their backoff delay (a virtual-thread sleep is
     * free), non-retryable failures skip the current route's remaining attempts
     * and advance the fallback chain, exactly mirroring the reactive engine.
     */
    private void streamWithPlan(List<ForwardingPolicy.Attempt> plan, int from,
                                String rawJson, ParsedRequest parsed, int timeout,
                                StreamSink sink, GatewayContext context, Map<String, String> pass,
                                long extraDelayMs) {
        if (from >= plan.size()) {
            throw new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE);
        }
        ForwardingPolicy.Attempt attempt = plan.get(from);
        sleepBefore(attempt, extraDelayMs);
        if (!breaker.allow(attempt.route())) {
            // Fail fast locally, then move on to the next viable attempt.
            CircuitOpenException open = new CircuitOpenException(attempt.route().id());
            int next = ForwardingPolicy.nextViable(plan, from, open);
            if (next < 0) {
                throw open;
            }
            streamWithPlan(plan, next, rawJson, parsed, timeout, sink, context, pass,
                    ForwardingPolicy.effectiveDelayMs(plan, from, next, open));
            return;
        }
        try {
            context.markUpstreamStart();
            provider.streamRawBlocking(
                    EngineRoutes.request(rawJson, parsed, attempt.route(), timeout, pass), sink);
            breaker.recordSuccess(attempt.route());
        } catch (RuntimeException e) {
            if (sink.chunkSent) {
                // Status and headers are committed: never splice a second
                // upstream's chunks into a stream the client already saw.
                // (chunkSent can only belong to THIS attempt: a chunked
                // failure of an earlier attempt already broke the chain.)
                throw e;
            }
            breaker.recordFailure(attempt.route());
            int next = ForwardingPolicy.nextViable(plan, from, e);
            if (next < 0) {
                throw e;
            }
            streamWithPlan(plan, next, rawJson, parsed, timeout, sink, context, pass,
                    ForwardingPolicy.effectiveDelayMs(plan, from, next, e));
        }
    }

    private record Completed(String body, Route route) {
    }

    private Completed completeWithPlan(List<ForwardingPolicy.Attempt> plan, int from,
                                       String rawJson, ParsedRequest parsed, int timeout,
                                       GatewayContext context, Map<String, String> pass,
                                       HeaderListener egress, long extraDelayMs) {
        if (from >= plan.size()) {
            throw new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE);
        }
        ForwardingPolicy.Attempt attempt = plan.get(from);
        sleepBefore(attempt, extraDelayMs);
        if (!breaker.allow(attempt.route())) {
            CircuitOpenException open = new CircuitOpenException(attempt.route().id());
            int next = ForwardingPolicy.nextViable(plan, from, open);
            if (next < 0) {
                throw open;
            }
            return completeWithPlan(plan, next, rawJson, parsed, timeout, context, pass, egress,
                    ForwardingPolicy.effectiveDelayMs(plan, from, next, open));
        }
        try {
            context.markUpstreamStart();
            String body = provider.completeRaw(
                    EngineRoutes.request(rawJson, parsed, attempt.route(), timeout, pass), egress);
            breaker.recordSuccess(attempt.route());
            return new Completed(body, attempt.route());
        } catch (RuntimeException e) {
            breaker.recordFailure(attempt.route());
            int next = ForwardingPolicy.nextViable(plan, from, e);
            if (next < 0) {
                throw e;
            }
            return completeWithPlan(plan, next, rawJson, parsed, timeout, context, pass, egress,
                    ForwardingPolicy.effectiveDelayMs(plan, from, next, e));
        }
    }

    private static void sleepBefore(ForwardingPolicy.Attempt attempt, long extraDelayMs) {
        long total = attempt.delayBeforeMs() + Math.max(0, extraDelayMs);
        if (total > 0) {
            try {
                Thread.sleep(total);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("retry backoff interrupted", e);
            }
        }
    }

    private void writeError(HttpServletResponse response, GatewayDecision decision) throws IOException {
        response.setStatus(decision.status());
        response.setContentType(EngineHeaders.APPLICATION_JSON);
        response.getOutputStream().write(
                ProtocolCodec.errorJson(decision.reason()).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /**
     * Upstream → client header forwarding. Called on the request thread before
     * any chunk, while the response is still uncommitted.
     */
    private HeaderListener egressListener(HttpServletResponse response) {
        return (status, headers) -> {
            if (response.isCommitted()) {
                return;
            }
            for (String name : headerPolicy.toClient(headers)) {
                List<String> values = headers.get(name);
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    response.addHeader(name, values.get(0));
                }
            }
        };
    }


    private static Map<String, String> firstValues(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        if (names == null) {
            return out;
        }
        for (String name : java.util.Collections.list(names)) {
            String value = request.getHeader(name);
            if (value != null) {
                out.put(name, value);
            }
        }
        return out;
    }

    /** One request = one virtual thread, so plain booleans are sufficient. */
    private static final class StreamSink implements ChunkSink {

        private final HttpServletResponse response;
        private final GatewayContext context;
        private final HeaderPolicy headerPolicy;
        private OutputStream out;
        private boolean chunkSent;
        private boolean doneWritten;
        private boolean errorWritten;

        StreamSink(HttpServletResponse response, GatewayContext context, HeaderPolicy headerPolicy) {
            this.response = response;
            this.context = context;
            this.headerPolicy = headerPolicy;
        }

        @Override
        public void onChunk(String rawSseLine) {
            if (rawSseLine.startsWith(":")) {
                // Transport-level comment frame (SSE heartbeat / upstream
                // comment): forward verbatim, but it is NOT protocol data --
                // no accounting, no first-chunk marking, no terminator check.
                write(rawSseLine);
                return;
            }
            boolean done = SseChunkAccounting.apply(context.kind(), rawSseLine, context);
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
        public void onHeaders(int status, Map<String, List<String>> headers) {
            // Called on the request thread before any chunk; the response is
            // still uncommitted (no flushBuffer before the first chunk).
            if (response.isCommitted()) {
                return;
            }
            for (String name : headerPolicy.toClient(headers)) {
                List<String> values = headers.get(name);
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    response.addHeader(name, values.get(0));
                }
            }
        }

        @Override
        public void onDone() {
            // Upstream reached a clean EOF. Intentionally do NOT synthesize a
            // terminator frame: that would disguise a truncated stream as a
            // success. handleStream() inspects isComplete() and reports it.
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
                // [DONE] follows only for protocols whose contract says so
                // (chat); the gateway never fabricates another protocol's
                // terminator, so responses/messages close bare.
                if (Protocols.of(context.kind()).appendDoneAfterInBandError() && !doneWritten) {
                    write(ProtocolCodec.doneLine());
                    doneWritten = true;
                }
            } catch (RuntimeException ignored) {
                // Best effort: the client may already be gone.
            }
        }

        /**
         * Synchronized: the upstream heartbeat watchdog may inject comment
         * frames from another thread while this virtual thread is between
         * chunks. Uncontended in the normal case (one writer), so the A/B
         * benchmark cost is a single monitor acquire per chunk.
         */
        private synchronized void write(String line) {
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
