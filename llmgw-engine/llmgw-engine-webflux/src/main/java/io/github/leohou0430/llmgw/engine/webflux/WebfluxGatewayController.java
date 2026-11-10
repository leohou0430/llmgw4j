package io.github.leohou0430.llmgw.engine.webflux;

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
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.ForwardingPolicy;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive data plane. Writes raw SSE lines straight through, no re-encoding.
 * <p>Both streaming and non-streaming paths are fully asynchronous: no blocking
 * HttpClient.send and no boundedElastic offload on the request path. This is
 * what makes the comparison against virtual threads a fair concurrency-model
 * experiment rather than a comparison against a handicapped reactive engine.
 *
 * <p>v12 multi-protocol: every POST endpoint (chat / responses / messages /
 * images / video-create) shares one handler parameterized by {@link ProtocolKind};
 * the kinds differ only in request parsing, SSE terminator and response-side
 * token/cache semantics, all of which hang off ParsedRequest.kind().
 *
 * <p>Streaming error contract (mirrors the servlet engine): a stream is
 * successful ONLY when the upstream emitted its protocol terminator
 * ({@code [DONE]} / {@code response.completed} / {@code message_stop}). A clean
 * EOF without it is a silent truncation. Any failure before the first chunk
 * becomes HTTP 502 + JSON; after the first chunk it becomes an in-band
 * {@code event: error}. The trailing {@code [DONE]} after an in-band error is
 * CHAT-only: the gateway never fabricates another protocol's terminator, so
 * responses/messages streams simply close. Fallback routes are only tried
 * before any chunk has been emitted. Once the terminator has been seen, a late
 * error is NOT a failure -- the protocol exchange is already complete.
 *
 * <p>Video tasks are asynchronous upstream jobs: create responses bind the task
 * id to the winning route ({@link TaskRouteSticky}), and poll/content GETs are
 * pinned to that route with NO fallback and NO circuit breaking -- a task exists
 * only on the upstream that created it. A STICKY MISS (unknown task id) resolves
 * the wildcard route and DOES honor the circuit breaker (v13): it is an
 * ordinary route call, and hammering a dead wildcard route must trip it.
 *
 * <p>v13 forwarding: attempt ordering (same-route retry with backoff, then the
 * fallback chain) is delegated to {@link ForwardingPolicy}; header pass-through
 * in both directions is delegated to {@link HeaderPolicy}. Comment lines
 * (": ...", SSE heartbeat) are transport-level and excluded from accounting,
 * TTFT and the first-chunk guard.
 */
@RestController
@Profile(EngineProfiles.WEBFLUX)
public class WebfluxGatewayController {

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

    public WebfluxGatewayController(GovernanceChain chain, Router router,
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
    public Mono<Void> handleChat(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.CHAT, request, response);
    }

    @PostMapping("/v1/responses")
    public Mono<Void> handleResponses(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.RESPONSES, request, response);
    }

    @PostMapping("/v1/messages")
    public Mono<Void> handleMessages(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.MESSAGES, request, response);
    }

    @PostMapping("/v1/images/generations")
    public Mono<Void> handleImages(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.IMAGES, request, response);
    }

    @PostMapping("/v1/videos")
    public Mono<Void> handleVideoCreate(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.VIDEOS, request, response);
    }

    // W4.3 protocol-surface expansion: JSON-in/JSON-out pass-through kinds.
    @PostMapping("/v1/embeddings")
    public Mono<Void> handleEmbeddings(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.EMBEDDINGS, request, response);
    }

    @PostMapping("/v1/rerank")
    public Mono<Void> handleRerank(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.RERANK, request, response);
    }

    /** JSON in, BINARY out: same byte-faithful path as video content. */
    @PostMapping("/v1/audio/speech")
    public Mono<Void> handleSpeech(ServerHttpRequest request, ServerHttpResponse response) {
        return handleBinaryKind(ProtocolKind.SPEECH, "audio/mpeg", request, response);
    }

    @GetMapping("/v1/videos/{id}")
    public Mono<Void> handleVideoPoll(@PathVariable("id") String id,
                                      ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id,
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/videos/{id}/content")
    public Mono<Void> handleVideoContent(@PathVariable("id") String id,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id + "/content",
                "video/mp4", id, UpstreamMethod.GET, request, response);
    }

    // W4.3 completion of the market parity list: uploads, files, batches.
    @PostMapping("/v1/audio/transcriptions")
    public Mono<Void> handleTranscriptions(ServerHttpRequest request, ServerHttpResponse response) {
        return handleUpload(ProtocolKind.TRANSCRIPTIONS, request, response);
    }

    @PostMapping("/v1/files")
    public Mono<Void> handleFileUpload(ServerHttpRequest request, ServerHttpResponse response) {
        return handleUpload(ProtocolKind.FILES, request, response);
    }

    @GetMapping("/v1/files")
    public Mono<Void> handleFileList(ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.FILES, "/files", EngineHeaders.APPLICATION_JSON,
                null, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/files/{id}")
    public Mono<Void> handleFileGet(@PathVariable("id") String id,
                                    ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.FILES, "/files/" + id,
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.GET, request, response);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/v1/files/{id}")
    public Mono<Void> handleFileDelete(@PathVariable("id") String id,
                                       ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.FILES, "/files/" + id,
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.DELETE, request, response);
    }

    @PostMapping("/v1/batches")
    public Mono<Void> handleBatchCreate(ServerHttpRequest request, ServerHttpResponse response) {
        return handleKind(ProtocolKind.BATCHES, request, response);
    }

    @GetMapping("/v1/batches")
    public Mono<Void> handleBatchList(ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.BATCHES, "/batches", EngineHeaders.APPLICATION_JSON,
                null, UpstreamMethod.GET, request, response);
    }

    @GetMapping("/v1/batches/{id}")
    public Mono<Void> handleBatchPoll(@PathVariable("id") String id,
                                      ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id,
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.GET, request, response);
    }

    // B11: market-parity pass-through endpoints (models catalog, batch cancel,
    // file content download). All honor sticky routing where a resource is
    // instance-local and the circuit breaker where it is not.
    @GetMapping("/v1/models")
    public Mono<Void> handleModels(ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.CHAT, "/models",
                EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET, request, response);
    }

    @PostMapping("/v1/batches/{id}/cancel")
    public Mono<Void> handleBatchCancel(@PathVariable("id") String id,
                                        ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id + "/cancel",
                EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.POST, request, response);
    }

    @GetMapping("/v1/files/{id}/content")
    public Mono<Void> handleFileContent(@PathVariable("id") String id,
                                        ServerHttpRequest request, ServerHttpResponse response) {
        return handleStickyFetch(ProtocolKind.FILES, "/files/" + id + "/content",
                "application/octet-stream", id, UpstreamMethod.GET, request, response);
    }

    private Mono<Void> handleKind(ProtocolKind kind, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        return Mono.defer(() -> {
            if (!requestSlots.tryAcquire()) {
                return writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            }
            return Mono.defer(() -> handleKindWithinCapacity(kind, request, response))
                    .doFinally(signal -> requestSlots.release());
        });
    }

    private Mono<Void> handleKindWithinCapacity(ProtocolKind kind, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        Map<String, String> clientHeaders = firstValues(request.getHeaders());
        return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE))
                .map(buffer -> {
                    String raw = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return raw;
                })
                .defaultIfEmpty("{}")
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(raw -> dispatch(kind, raw,
                        request.getHeaders().getFirst("Authorization"), clientHeaders, response,
                        request.getHeaders().getFirst(EngineHeaders.REQUEST_ID), arrived))
                .onErrorResume(DataBufferLimitException.class,
                        e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
    }

    /**
     * W4.3 binary-response kind (speech): full governance, then a byte-for-byte
     * pass-through. The bytes never round-trip a String. JSON errors before the
     * first byte keep the standard gateway error contract.
     */
    private Mono<Void> handleBinaryKind(ProtocolKind kind, String contentType,
                                        ServerHttpRequest request, ServerHttpResponse response) {
        return Mono.defer(() -> {
            if (!requestSlots.tryAcquire()) {
                return writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            }
            return Mono.defer(() -> handleBinaryKindWithinCapacity(kind, contentType, request, response))
                    .doFinally(signal -> requestSlots.release());
        });
    }

    private Mono<Void> handleBinaryKindWithinCapacity(ProtocolKind kind, String contentType,
                                        ServerHttpRequest request, ServerHttpResponse response) {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        Map<String, String> clientHeaders = firstValues(request.getHeaders());
        return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE))
                .map(buffer -> {
                    String raw = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return raw;
                })
                .defaultIfEmpty("{}")
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(rawJson -> {
                    String requestId = GatewayPipeline.requestId(
                            request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
                    response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
                    GatewayDecision decision = GatewayPipeline.enter(chain, router, kind,
                            request.getHeaders().getFirst("Authorization"), rawJson);
                    if (!decision.allowed()) {
                        return writeError(response, decision);
                    }
                    GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
                    Route route = decision.route();
                    Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
                    AtomicBoolean settled = new AtomicBoolean(false);
                    return Mono.defer(() -> {
                                if (!breaker.allow(route)) {
                                    return Mono.<byte[]>error(new CircuitOpenException(route.id()));
                                }
                                context.markUpstreamStart();
                                return Mono.fromFuture(() -> provider.fetchRawAsync(EngineRoutes.postBytes(
                                        rawJson, decision.parsed(), route,
                                        properties.getUpstream().getTimeoutMs(), pass)));
                            })
                            .doOnSuccess(body -> breaker.recordSuccess(route))
                            .doOnError(e -> {
                                if (!(e instanceof CircuitOpenException)) {
                                    breaker.recordFailure(route);
                                }
                            })
                            .flatMap(body -> {
                                context.markUpstreamEnd();
                                response.getHeaders().set(EngineHeaders.OVERHEAD_MS,
                                        EngineTimings.total(context));
                                response.setStatusCode(HttpStatus.OK);
                                response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
                                return response.writeWith(Mono.just(
                                        response.bufferFactory().wrap(body)));
                            })
                            .onErrorResume(e -> {
                                finishOnce(context, settled, false);
                                return writeError(response, GatewayDecision.deny(502,
                                        EngineReasons.upstream(e)));
                            })
                            .doFinally(signal -> finishOnce(context, settled,
                                    signal == SignalType.ON_COMPLETE));
                })
                .onErrorResume(DataBufferLimitException.class,
                        e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
    }

    private Mono<Void> dispatch(ProtocolKind kind, String rawJson, String auth,
                                Map<String, String> clientHeaders,
                                ServerHttpResponse response, String incomingRequestId,
                                long arrivedNanos) {
        String requestId = GatewayPipeline.requestId(incomingRequestId);
        response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
        GatewayDecision decision = GatewayPipeline.enter(chain, router, kind, auth, rawJson);
        if (!decision.allowed()) {
            return writeError(response, decision);
        }
        ParsedRequest parsed = decision.parsed();
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrivedNanos);
        if (!EngineRoutes.ownsReferencedFiles(parsed, decision.keyId(), decision.route(), taskRoutes)) {
            GatewayPipeline.finish(chain, context, false);
            return writeError(response, GatewayDecision.deny(404, "referenced file not found on this route"));
        }
        Route route = decision.route();
        int timeout = properties.getUpstream().getTimeoutMs();
        ForwardingPolicy.RetryConfig retry = retryConfig;
        Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
        HeaderListener egress = egressListener(response);
        AtomicBoolean settled = new AtomicBoolean(false);

        if (!parsed.stream()) {
            // B8: explicit per-request cache bypass header (also skips the put).
            boolean cacheBypass = "1".equals(clientHeaders.get("x-llmgw-no-cache"));
            Optional<String> cached = cacheBypass
                    ? Optional.empty()
                    : chain.lookupCache(decision.keyId(), parsed);
            Mono<String> bodyMono;
            AtomicReference<Route> winner = new AtomicReference<>(route);
            if (cached.isPresent()) {
                // Tokens are accounted once, in the shared flatMap below.
                // Adding them here as well double-billed cache hits.
                context.markCached();
                bodyMono = Mono.just(cached.get());
            } else {
                bodyMono = completeAttempts(
                                EngineRoutes.forwardPlan(router, route, retry, parsed), 0,
                                rawJson, parsed, timeout, context, winner, pass, egress)
                        .map(Completed::body);
            }
            return bodyMono
                    .flatMap(body -> {
                        context.markUpstreamEnd();
                        // Single parse: token count AND cacheability come from
                        // the same tree. The body previously went through
                        // readTree twice more (tokens + cache check).
                        JsonNode root = ProtocolCodec.tryParse(body);
                        context.setCompletionTokensIfAbsent(
                                ProtocolCodec.completionTokensFromResponse(kind, root));
                        if (cached.isEmpty() && !cacheBypass) {
                            chain.cache(decision.keyId(), parsed, body,
                                    ProtocolCodec.isCacheableResponse(kind, root));
                            // A video job lives on the upstream that created it:
                            // pin the task id (async-job protocols only) to the
                            // route that served the create.
                        }
                        String taskId = cached.isEmpty() ? Protocols.of(kind).taskId(root) : "";
                        if (!taskId.isEmpty()) {
                            taskRoutes.bind(decision.keyId(), kind.wireName(), taskId, winner.get().id());
                        }
                        return writeJson(response, body, context);
                    })
                    .onErrorResume(e -> {
                        finishOnce(context, settled, false);
                        return writeError(response, GatewayDecision.deny(502,
                                EngineReasons.upstream(e)));
                    })
                    // doFinally also sees CANCEL, which produces NO terminal
                    // signal. Without it a client disconnect silently skipped
                    // billing/audit and leaked the quota reservation.
                    .doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
        }

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().setCacheControl(EngineHeaders.NO_CACHE);
        // writeWith() commits headers BEFORE subscribing to the body, so a
        // streaming response can only report work done up to this point.
        // Complete TTFT/upstream timings live in llmgw.ttft / llmgw.upstream.
        response.getHeaders().set(EngineHeaders.OVERHEAD_MS,
                EngineTimings.preflight(context));

        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean doneSeen = new AtomicBoolean(false);
        Flux<String> chunks = streamAttempts(
                        EngineRoutes.forwardPlan(router, route, retry, parsed), 0,
                        rawJson, parsed, timeout, started, context, pass, egress)
                .doOnNext(line -> {
                    // Comment frames (": ...") are transport-level and must not
                    // touch the protocol accounting or the terminator verdict.
                    if (!line.startsWith(":") && SseChunkAccounting.apply(kind, line, context)) {
                        doneSeen.set(true);
                    }
                })
                .switchOnFirst((signal, rest) -> {
                    if (signal.hasError()) {
                        // No chunk was ever emitted: the status can still change.
                        finishOnce(context, settled, false);
                        return firstChunkError(response, signal.getThrowable(), kind);
                    }
                    if (signal.isOnComplete()) {
                        // Not even one chunk: an empty stream is a truncation,
                        // never a success with a fabricated terminator.
                        finishOnce(context, settled, false);
                        return firstChunkError(response, truncated(kind), kind);
                    }
                    // Append the completion check AFTER the upstream completes
                    // normally. concatWith(Flux.defer) reads doneSeen at that
                    // point rather than at assembly time.
                    return rest.concatWith(Flux.defer(() -> {
                        if (doneSeen.get()) {
                            return Flux.empty();
                        }
                        finishOnce(context, settled, false);
                        if (!started.get() && !response.isCommitted()) {
                            return firstChunkError(response, truncated(kind), kind);
                        }
                        return terminalError(EngineReasons.truncated(kind), kind);
                    }));
                })
                .onErrorResume(e -> {
                    if (doneSeen.get()) {
                        // The upstream already delivered its terminator. A
                        // disconnect afterwards must not turn a complete answer
                        // into a failed one, nor append a spurious error event.
                        finishOnce(context, settled, true);
                        return Flux.empty();
                    }
                    finishOnce(context, settled, false);
                    if (started.get()) {
                        return terminalError(EngineReasons.upstream(e), kind);
                    }
                    return firstChunkError(response, e, kind);
                })
                .doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));

        Flux<DataBuffer> body = chunks.map(line -> response.bufferFactory()
                .wrap(line.getBytes(StandardCharsets.UTF_8)));
        return response.writeWith(body);
    }

    private static IllegalStateException truncated(ProtocolKind kind) {
        return new IllegalStateException(ProtocolCodec.truncatedMessage(kind));
    }

    /**
     * In-band error after the first chunk. {@code [DONE]} follows only for
     * protocols whose contract says so (chat): fabricating another protocol's
     * terminator would corrupt the client's protocol view of the stream.
     */
    private static Flux<String> terminalError(String reason, ProtocolKind kind) {
        return Protocols.of(kind).appendDoneAfterInBandError()
                ? Flux.just(ProtocolCodec.errorEvent(reason), ProtocolCodec.doneLine())
                : Flux.just(ProtocolCodec.errorEvent(reason));
    }

    private Flux<String> firstChunkError(ServerHttpResponse response, Throwable error,
                                         ProtocolKind kind) {
        String reason = EngineReasons.upstream(error);
        if (response.isCommitted()) {
            // Defensive: headers already escaped, so fail in band instead.
            return terminalError(reason, kind);
        }
        response.setStatusCode(HttpStatus.BAD_GATEWAY);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_STORE);
        return Flux.just(ProtocolCodec.errorJson(reason));
    }

    /**
     * The attempt chain. Errors advance per {@link ForwardingPolicy#nextViable};
     * once a chunk reached the client ({@code started}), no further attempt is
     * ever spliced in. Backoff delays for same-route retries are applied lazily
     * (Mono.delay), so a first-attempt success pays nothing.
     */
    private Flux<String> streamAttempts(List<ForwardingPolicy.Attempt> plan, int from,
                                        String rawJson, ParsedRequest parsed, int timeout,
                                        AtomicBoolean started, GatewayContext context,
                                        Map<String, String> pass, HeaderListener egress) {
        if (from >= plan.size()) {
            return Flux.error(new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE));
        }
        ForwardingPolicy.Attempt attempt = plan.get(from);
        Flux<String> self = streamAttempt(rawJson, parsed, attempt.route(), timeout,
                started, context, pass, egress);
        return self.onErrorResume(e -> {
            if (started.get()) {
                return Flux.error(e);
            }
            int next = ForwardingPolicy.nextViable(plan, from, e);
            if (next < 0) {
                return Flux.error(e);
            }
            Flux<String> rest = streamAttempts(plan, next, rawJson, parsed, timeout,
                    started, context, pass, egress);
            long delay = ForwardingPolicy.effectiveDelayMs(plan, from, next, e);
            return delay > 0 ? Mono.delay(Duration.ofMillis(delay)).thenMany(rest) : rest;
        });
    }

    private Flux<String> streamAttempt(String rawJson, ParsedRequest parsed, Route route,
                                       int timeout, AtomicBoolean started, GatewayContext context,
                                       Map<String, String> pass, HeaderListener egress) {
        AtomicBoolean chunked = new AtomicBoolean(false);
        return Flux.defer(() -> {
            if (!breaker.allow(route)) {
                // Local failure, no network I/O: the fallback chain moves on.
                return Flux.<String>error(new CircuitOpenException(route.id()));
            }
            context.markUpstreamStart();
            return upstreamLines(EngineRoutes.request(rawJson, parsed, route, timeout, pass), egress)
                    .doOnNext(line -> {
                        // Comment frames are transport-level: never a first chunk,
                        // never a fallback trigger, invisible to TTFT.
                        if (!line.isBlank() && !line.startsWith(":")) {
                            if (chunked.compareAndSet(false, true)) {
                                context.markFirstChunk();
                            }
                            started.set(true);
                        }
                    })
                    // Connectivity verdicts only. Delivering data = healthy,
                    // no data + exception = failed attempt. A downstream cancel
                    // records nothing (the client went away, upstream is fine).
                    .doOnComplete(() -> {
                        if (chunked.get()) {
                            breaker.recordSuccess(route);
                        }
                    })
                    .doOnError(e -> {
                        if (!chunked.get()) {
                            breaker.recordFailure(route);
                        }
                    });
        });
    }

    private record Completed(String body, Route route) {
    }

    /**
     * Async equivalent of the servlet engine's blocking fallback loop. Every
     * attempt stays lazy, so a cache hit or a cancelled subscription never
     * issues an upstream request, and Netty's event loop is never blocked.
     * The winning route is captured for video-task stickiness.
     */
    private Mono<Completed> completeAttempts(List<ForwardingPolicy.Attempt> plan, int from,
                                             String rawJson, ParsedRequest parsed, int timeout,
                                             GatewayContext context, AtomicReference<Route> winner,
                                             Map<String, String> pass, HeaderListener egress) {
        if (from >= plan.size()) {
            return Mono.error(new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE));
        }
        ForwardingPolicy.Attempt attempt = plan.get(from);
        Mono<Completed> self = Mono.defer(() -> {
            if (!breaker.allow(attempt.route())) {
                return Mono.<Completed>error(new CircuitOpenException(attempt.route().id()));
            }
            context.markUpstreamStart();
            return Mono.fromFuture(() -> provider.completeRawAsync(
                            EngineRoutes.request(rawJson, parsed, attempt.route(), timeout, pass), egress))
                    .map(body -> {
                        winner.set(attempt.route());
                        return new Completed(body, attempt.route());
                    })
                    .doOnSuccess(c -> breaker.recordSuccess(attempt.route()))
                    .doOnError(e -> breaker.recordFailure(attempt.route()));
        });
        return self.onErrorResume(e -> {
            int next = ForwardingPolicy.nextViable(plan, from, e);
            if (next < 0) {
                return Mono.error(e);
            }
            Mono<Completed> rest = completeAttempts(plan, next, rawJson, parsed, timeout,
                    context, winner, pass, egress);
            long delay = ForwardingPolicy.effectiveDelayMs(plan, from, next, e);
            return delay > 0 ? Mono.delay(Duration.ofMillis(delay)).then(rest) : rest;
        });
    }

    /**
     * Task poll / fetch GET/DELETE (video, batch, files): governance + sticky
     * routing, then a raw byte-for-byte pass-through. A PINNED task has no
     * fallback and no circuit breaking -- the task exists only on the upstream
     * that created it, and a create-route circuit must never strand a live
     * job's polling. A STICKY MISS resolves the wildcard route, which is an
     * ordinary route call and DOES honor the circuit breaker (v13). A null
     * taskId (list endpoints) is always unpinned.
     */
    private Mono<Void> handleStickyFetch(ProtocolKind kind, String upstreamPath, String contentType,
                                         String taskId, UpstreamMethod method,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        return Mono.defer(() -> {
            if (!requestSlots.tryAcquire()) {
                return writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            }
            return Mono.defer(() -> handleStickyFetchWithinCapacity(kind, upstreamPath, contentType, taskId, method, request, response))
                    .doFinally(signal -> requestSlots.release());
        });
    }

    private Mono<Void> handleStickyFetchWithinCapacity(ProtocolKind kind, String upstreamPath, String contentType,
                                         String taskId, UpstreamMethod method,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        return Mono.defer(() -> handleScopedFetch(kind, upstreamPath, contentType,
                taskId, method, request, response))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Mono<Void> handleScopedFetch(ProtocolKind kind, String upstreamPath, String contentType,
                                         String taskId, UpstreamMethod method,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        long arrived = System.nanoTime();
        String requestId = GatewayPipeline.requestId(
                request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
        response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
        ParsedRequest parsed = ParsedRequest.bodyless(kind);
        GatewayDecision admission = chain.enter(
                request.getHeaders().getFirst("Authorization"), parsed, router.resolve(kind, "*"));
        if (!admission.allowed()) {
            return writeError(response, admission);
        }
        Route pinned = taskId == null ? null
                : router.byId(taskRoutes.resolve(admission.keyId(), kind.wireName(), taskId));
        if ((taskId != null && pinned == null)
                || (taskId == null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES))) {
            GatewayPipeline.finish(chain, GatewayPipeline.context(admission, requestId, arrived), false);
            return writeError(response, GatewayDecision.deny(taskId == null ? 501 : 404,
                    taskId == null ? "tenant-scoped resource listing is unavailable" : "resource not found"));
        }
        boolean isPinned = pinned != null;
        Route route = isPinned ? pinned : admission.route();
        GatewayDecision decision = admission.withRoute(route);
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        AtomicBoolean settled = new AtomicBoolean(false);
        return Mono.defer(() -> {
                    if (!isPinned && !breaker.allow(route)) {
                        return Mono.<byte[]>error(new CircuitOpenException(route.id()));
                    }
                    context.markUpstreamStart();
                    return Mono.fromFuture(() -> provider.fetchRawAsync(EngineRoutes.methodFetch(route, upstreamPath,
                            method, properties.getUpstream().getTimeoutMs())));
                })
                .doOnSuccess(body -> {
                    if (!isPinned) {
                        breaker.recordSuccess(route);
                    }
                })
                .doOnError(e -> {
                    if (!isPinned && !(e instanceof CircuitOpenException)) {
                        breaker.recordFailure(route);
                    }
                })
                .flatMap(body -> {
                    context.markUpstreamEnd();
                    String upstream = EngineTimings.upstreamOrNull(context);
                    if (upstream != null) {
                        response.getHeaders().set(EngineHeaders.UPSTREAM_MS, upstream);
                    }
                    response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
                    response.setStatusCode(HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
                    DataBuffer buffer = response.bufferFactory().wrap(body);
                    return response.writeWith(Mono.just(buffer));
                })
                .onErrorResume(e -> {
                    finishOnce(context, settled, false);
                    return writeError(response, GatewayDecision.deny(502, EngineReasons.upstream(e)));
                })
                .doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
    }

    /**
     * W4.3 byte-body upload kinds (transcriptions, files): full governance via
     * the byte-probe, then a byte-faithful POST. The multipart Content-Type
     * (with its boundary) rides the policy pass-through untouched.
     */
    private Mono<Void> handleUpload(ProtocolKind kind, ServerHttpRequest request,
                                    ServerHttpResponse response) {
        return Mono.defer(() -> {
            if (!requestSlots.tryAcquire()) {
                return writeError(response, GatewayDecision.deny(503, "gateway request capacity exceeded"));
            }
            return Mono.defer(() -> handleUploadWithinCapacity(kind, request, response))
                    .doFinally(signal -> requestSlots.release());
        });
    }

    private Mono<Void> handleUploadWithinCapacity(ProtocolKind kind, ServerHttpRequest request,
                                    ServerHttpResponse response) {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        Map<String, String> clientHeaders = firstValues(request.getHeaders());
        return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.toByteBuffer().get(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(body -> {
                    String requestId = GatewayPipeline.requestId(
                            request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
                    response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
                    ParsedRequest parsed;
                    try {
                        parsed = Protocols.of(kind).parse(body);
                    } catch (RuntimeException e) {
                        return writeError(response, GatewayDecision.deny(400,
                                "invalid multipart body: " + e.getMessage()));
                    }
                    GatewayDecision decision = chain.enter(
                            request.getHeaders().getFirst("Authorization"), parsed,
                            router.resolve(kind, parsed.model()));
                    if (!decision.allowed()) {
                        return writeError(response, decision);
                    }
                    GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
                    Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
                    AtomicBoolean settled = new AtomicBoolean(false);
                    return Mono.defer(() -> {
                                if (!breaker.allow(decision.route())) {
                                    return Mono.<byte[]>error(
                                            new CircuitOpenException(decision.route().id()));
                                }
                                context.markUpstreamStart();
                                return Mono.fromFuture(() -> provider.fetchRawAsync(EngineRoutes.postRawBytes(body,
                                        parsed, decision.route(),
                                        properties.getUpstream().getTimeoutMs(), pass)));
                            })
                            .doOnSuccess(ignored -> breaker.recordSuccess(decision.route()))
                            .doOnError(e -> {
                                if (!(e instanceof CircuitOpenException)) {
                                    breaker.recordFailure(decision.route());
                                }
                            })
                            .flatMap(bytes -> {
                                context.markUpstreamEnd();
                                if (kind == ProtocolKind.FILES) {
                                    String resourceId = ProtocolCodec.tryParse(new String(bytes, StandardCharsets.UTF_8))
                                            .path("id").asText("");
                                    if (!resourceId.isBlank()) {
                                        taskRoutes.bind(decision.keyId(), kind.wireName(), resourceId, decision.route().id());
                                    }
                                }
                                response.getHeaders().set(EngineHeaders.OVERHEAD_MS,
                                        EngineTimings.total(context));
                                response.setStatusCode(HttpStatus.OK);
                                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                return response.writeWith(Mono.just(
                                        response.bufferFactory().wrap(bytes)));
                            })
                            .onErrorResume(e -> {
                                finishOnce(context, settled, false);
                                return writeError(response, GatewayDecision.deny(502,
                                        EngineReasons.upstream(e)));
                            })
                            .doFinally(signal -> finishOnce(context, settled,
                                    signal == SignalType.ON_COMPLETE));
                })
                .onErrorResume(DataBufferLimitException.class,
                        e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
    }

    private Mono<Void> writeJson(ServerHttpResponse response, String body,
                                 GatewayContext context) {
        String upstream = EngineTimings.upstreamOrNull(context);
        if (upstream != null) {
            response.getHeaders().set(EngineHeaders.UPSTREAM_MS, upstream);
        }
        response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> writeError(ServerHttpResponse response, GatewayDecision decision) {
        response.setStatusCode(HttpStatus.valueOf(decision.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = ProtocolCodec.errorJson(decision.reason());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private void finishOnce(GatewayContext context, AtomicBoolean settled, boolean success) {
        if (settled.compareAndSet(false, true)) {
            GatewayPipeline.finish(chain, context, success);
        }
    }

    /**
     * Upstream → client header forwarding. The listener fires on the HTTP
     * client's response thread strictly before any body byte flows; writes
     * then happen-before the controller's body writes (Reactor queue HB), so
     * mutating the still-uncommitted headers here is race-free. A cache hit
     * never fires a listener -- cached responses carry no upstream headers.
     */
    private HeaderListener egressListener(ServerHttpResponse response) {
        return (status, headers) -> {
            if (response.isCommitted()) {
                return;
            }
            for (String name : headerPolicy.toClient(headers)) {
                List<String> values = headers.get(name);
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    response.getHeaders().add(name, values.get(0));
                }
            }
        };
    }


    private static Map<String, String> firstValues(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        // Spring Framework 7: HttpHeaders no longer implements Map.
        for (String name : headers.headerNames()) {
            String value = headers.getFirst(name);
            if (value != null) {
                out.put(name, value);
            }
        }
        return out;
    }
    private Flux<String> upstreamLines(io.github.leohou0430.llmgw.core.upstream.UpstreamRequest request, HeaderListener listener) {
        return Flux.defer(() -> {
            Flux<String> lines = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(provider.streamRaw(request, listener));
            int heartbeatMs = properties.getUpstream().getHeartbeatIntervalMs();
            if (heartbeatMs <= 0) return lines;
            var ended = reactor.core.publisher.Sinks.<Void>one();
            return lines.doFinally(signal -> ended.tryEmitEmpty()).mergeWith(
                    Flux.interval(java.time.Duration.ofMillis(heartbeatMs)).map(tick -> ": keep-alive\n\n")
                            .takeUntilOther(ended.asMono()));
        });
    }
}
