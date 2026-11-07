package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.protocol.VideosProtocol;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.routing.StickyTaskRoutes;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamProvider;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
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
import java.util.List;
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
 * id to the winning route ({@link StickyTaskRoutes}), and poll/content GETs are
 * pinned to that route with NO fallback and NO circuit breaking -- a task exists
 * only on the upstream that created it.
 */
@RestController
@Profile(EngineProfiles.WEBFLUX)
public class WebfluxGatewayController {

    private final GovernanceChain chain;
    private final Router router;
    private final UpstreamProvider provider;
    private final CircuitBreaker breaker;
    private final GatewayProperties properties;
    private final StickyTaskRoutes videoTasks;

    public WebfluxGatewayController(GovernanceChain chain, Router router,
                                    UpstreamProvider provider, CircuitBreaker breaker,
                                    GatewayProperties properties) {
        this.chain = chain;
        this.router = router;
        this.provider = provider;
        this.breaker = breaker;
        this.properties = properties;
        this.videoTasks = new StickyTaskRoutes(
                properties.getVideos().getStickyMaxEntries(),
                properties.getVideos().getStickyTtlSeconds());
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

    @GetMapping("/v1/videos/{id}")
    public Mono<Void> handleVideoPoll(@PathVariable("id") String id,
                                      ServerHttpRequest request, ServerHttpResponse response) {
        return handleVideoFetch(id, "/videos/" + id, EngineHeaders.APPLICATION_JSON, request, response);
    }

    @GetMapping("/v1/videos/{id}/content")
    public Mono<Void> handleVideoContent(@PathVariable("id") String id,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        return handleVideoFetch(id, "/videos/" + id + "/content", "video/mp4", request, response);
    }

    private Mono<Void> handleKind(ProtocolKind kind, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        long arrived = System.nanoTime();
        long max = Math.max(1, properties.getMaxRequestBytes());
        return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE))
                .map(buffer -> {
                    String raw = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return raw;
                })
                .defaultIfEmpty("{}")
                .flatMap(raw -> dispatch(kind, raw,
                        request.getHeaders().getFirst("Authorization"), response,
                        request.getHeaders().getFirst(EngineHeaders.REQUEST_ID), arrived))
                .onErrorResume(DataBufferLimitException.class,
                        e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
    }

    private Mono<Void> dispatch(ProtocolKind kind, String rawJson, String auth,
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
        Route route = decision.route();
        int timeout = properties.getUpstream().getTimeoutMs();
        AtomicBoolean settled = new AtomicBoolean(false);

        if (!parsed.stream()) {
            Optional<String> cached = chain.lookupCache(decision.keyId(), parsed);
            Mono<String> bodyMono;
            AtomicReference<Route> winner = new AtomicReference<>(route);
            if (cached.isPresent()) {
                // Tokens are accounted once, in the shared flatMap below.
                // Adding them here as well double-billed cache hits.
                context.markCached();
                bodyMono = Mono.just(cached.get());
            } else {
                bodyMono = completeAsyncWithFallback(rawJson, parsed, route, timeout, context, winner)
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
                        if (cached.isEmpty()) {
                            chain.cache(decision.keyId(), parsed, body,
                                    ProtocolCodec.isCacheableResponse(kind, root));
                            // A video job lives on the upstream that created it:
                            // pin the task id to the route that served the create.
                            if (kind == ProtocolKind.VIDEOS) {
                                videoTasks.bind(VideosProtocol.taskId(root), winner.get().id());
                            }
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
        Flux<String> chunks = streamWithFallback(rawJson, parsed, route, timeout, started, context)
                .doOnNext(line -> {
                    if (SseChunkAccounting.apply(kind, line, context)) {
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
     * In-band error after the first chunk. {@code [DONE]} follows for CHAT only:
     * it is chat's own terminator, and fabricating response.completed or
     * message_stop would corrupt the client's protocol view of the stream.
     */
    private static Flux<String> terminalError(String reason, ProtocolKind kind) {
        return kind == ProtocolKind.CHAT
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

    private Flux<String> streamWithFallback(String rawJson, ParsedRequest parsed,
                                            Route primary, int timeout, AtomicBoolean started,
                                            GatewayContext context) {
        Flux<String> flux = attempt(rawJson, parsed, primary, timeout, started, context);
        for (Route fallback : router.fallbacks(primary)) {
            flux = flux.onErrorResume(e -> started.get()
                    ? Flux.<String>error(e)
                    : attempt(rawJson, parsed, fallback, timeout, started, context));
        }
        return flux;
    }

    private Flux<String> attempt(String rawJson, ParsedRequest parsed, Route route,
                                 int timeout, AtomicBoolean started, GatewayContext context) {
        AtomicBoolean chunked = new AtomicBoolean(false);
        return Flux.defer(() -> {
            if (!breaker.allow(route)) {
                // Local failure, no network I/O: the fallback chain moves on.
                return Flux.<String>error(new CircuitOpenException(route.id()));
            }
            context.markUpstreamStart();
            return provider.streamRaw(EngineRoutes.request(rawJson, parsed, route, timeout))
                    .doOnNext(line -> {
                        if (!line.isBlank()) {
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
    private Mono<Completed> completeAsyncWithFallback(String rawJson, ParsedRequest parsed,
                                                      Route primary, int timeout,
                                                      GatewayContext context,
                                                      AtomicReference<Route> winner) {
        List<Route> candidates = EngineRoutes.candidates(router, primary);
        Mono<Completed> result = null;
        for (Route route : candidates) {
            Mono<Completed> attempt = Mono.defer(() -> {
                if (!breaker.allow(route)) {
                    return Mono.<Completed>error(new CircuitOpenException(route.id()));
                }
                context.markUpstreamStart();
                return provider.completeRawAsync(EngineRoutes.request(rawJson, parsed, route, timeout))
                        .map(body -> {
                            winner.set(route);
                            return new Completed(body, route);
                        })
                        .doOnSuccess(c -> breaker.recordSuccess(route))
                        .doOnError(e -> breaker.recordFailure(route));
            });
            result = result == null ? attempt : result.onErrorResume(e -> attempt);
        }
        return result == null
                ? Mono.error(new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE))
                : result;
    }

    /**
     * Video poll / content GET: governance + sticky routing, then a raw
     * byte-for-byte pass-through. No cache, no token accounting, no fallback,
     * no circuit breaker — the task is pinned to one upstream or it does not
     * exist; a create-route circuit must never strand a live job's polling.
     */
    private Mono<Void> handleVideoFetch(String taskId, String upstreamPath, String contentType,
                                        ServerHttpRequest request, ServerHttpResponse response) {
        long arrived = System.nanoTime();
        String requestId = GatewayPipeline.requestId(
                request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
        response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
        ParsedRequest parsed = ParsedRequest.bodyless(ProtocolKind.VIDEOS);
        Route pinned = router.byId(videoTasks.resolve(taskId));
        Route route = pinned != null ? pinned : router.resolve(ProtocolKind.VIDEOS, "*");
        GatewayDecision decision = chain.enter(
                request.getHeaders().getFirst("Authorization"), parsed, route);
        if (!decision.allowed()) {
            return writeError(response, decision);
        }
        GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
        AtomicBoolean settled = new AtomicBoolean(false);
        return Mono.defer(() -> {
                    context.markUpstreamStart();
                    return provider.fetchRawAsync(EngineRoutes.fetch(route, upstreamPath,
                            properties.getUpstream().getTimeoutMs()));
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
}
