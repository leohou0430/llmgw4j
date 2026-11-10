package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamProvider;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One upstream client for BOTH engines, so the benchmark has a single variable.
 * WebFlux uses sendAsync for streaming (fromLineSubscriber) AND non-streaming
 * (ofString); virtual threads use blocking send + ofLines/ofString.
 * Lines and bodies are forwarded verbatim; no JSON re-encoding.
 *
 * <p>v5 fixes three correctness/resource bugs on the streaming path:
 * <ul>
 *   <li>Status is checked in the {@code BodyHandler.apply(ResponseInfo)} hook,
 *       BEFORE the line subscriber is attached. Previously a 429/500 error body
 *       was emitted as normal {@code data:} lines and the later status check was
 *       swallowed because the Flux had already completed.</li>
 *   <li>Downstream demand is bridged to the JDK {@code Flow.Subscription}; the
 *       old {@code request(Long.MAX_VALUE)} plus an unbounded create buffer let
 *       a slow client grow the heap without limit.</li>
 *   <li>Cancellation cancels the in-flight future AND the body subscription.
 *       JDK HttpClient cannot hard-abort a socket on cancel, but cancelling the
 *       BodySubscriber releases the connection instead of leaving it streaming
 *       into a dead sink until the 60s timeout. IMPORTANT: this hook is wired
 *       to onCancel ONLY. Reactor also fires onDispose on a NORMAL complete;
 *       cancelling the future there tears down a shared HTTP/1.1 connection
 *       while sibling requests are still in flight, and they fail with
 *       "Request cancelled" (measured: 16% SSE failures at 50 VU).</li>
 * </ul>
 *
 * <p>v13 upstream hardening (all off by default; defaults keep v12 behavior
 * and the perf baseline identical):
 * <ul>
 *   <li>Response headers are observable via {@link HeaderListener} at the
 *       {@code BodyHandler.apply} hook / right after {@code send()} returns --
 *       the only point where they exist before the body flows.</li>
 *   <li>First-byte + idle-read watchdogs on streaming calls: a hung upstream
 *       fails BEFORE the 200 is committed (fallback still possible), a
 *       mid-stream stall aborts instead of hanging until the whole-request
 *       timeout. The watchdog interrupts the calling virtual thread on the
 *       blocking path and cancels the future on the reactive path.</li>
 *   <li>SSE heartbeat: an SSE comment frame injected on idle streams so
 *       intermediate proxies stop severing long generations. Comment frames
 *       are the ONLY bytes this gateway ever fabricates, and only when
 *       {@code heartbeat-interval-ms > 0}.</li>
 * </ul>
 */
@Component
public class JdkHttpUpstream implements UpstreamProvider {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpUpstream.class);

    private static final String HEARTBEAT_FRAME = ": keep-alive\n\n";

    private final HttpClient client;
    private final GatewayProperties properties;
    /** Target URLs come from static route config; build() used to compile a regex
     * and re-parse a URI on EVERY request. Resolve once per distinct URL. */
    private final ConcurrentMap<String, URI> targetUris = new ConcurrentHashMap<>();
    /** Only non-null when we created a dedicated executor (executor-threads > 0). */
    private final java.util.concurrent.ExecutorService ownedExecutor;
    /**
     * Shared watchdog for first-byte/idle timeouts and blocking-path heartbeats.
     * Created only when any of them is enabled, so the default config pays for
     * exactly zero extra threads or scheduled tasks.
     */
    private final ScheduledThreadPoolExecutor watchdog;
    /**
     * v13 provider dialects: first authenticator whose supports(provider) is
     * true wins; none -> OpenAI-compatible Bearer (the historical default).
     */
    private final List<io.github.leohou0430.llmgw.core.upstream.UpstreamAuthenticator> authenticators;

    public JdkHttpUpstream(GatewayProperties properties,
                           org.springframework.beans.factory.ObjectProvider<io.github.leohou0430.llmgw.core.upstream.UpstreamAuthenticator> authenticators) {
        this.properties = properties;
        this.authenticators = authenticators == null
                ? List.of()
                : authenticators.orderedStream().toList();
        int threads = Math.max(0, properties.getUpstream().getExecutorThreads());
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(
                        Math.max(1, properties.getUpstream().getConnectTimeoutMs())));
        if (threads > 0) {
            this.ownedExecutor = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "llmgw-upstream-http");
                t.setDaemon(true);
                return t;
            });
            builder.executor(this.ownedExecutor);
        } else {
            this.ownedExecutor = null;
        }
        this.client = builder.build();
        boolean needsWatchdog = properties.getUpstream().getFirstByteTimeoutMs() > 0
                || properties.getUpstream().getIdleReadTimeoutMs() > 0
                || properties.getUpstream().getHeartbeatIntervalMs() > 0;
        if (needsWatchdog) {
            this.watchdog = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "llmgw-upstream-watchdog");
                t.setDaemon(true);
                return t;
            });
            this.watchdog.setRemoveOnCancelPolicy(true);
        } else {
            this.watchdog = null;
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    @Override
    public String id() {
        return "jdk-http-" + properties.getUpstream().getType();
    }

    @Override
    public Flux<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
        HttpRequest http = build(request, true);
        int heartbeatMs = properties.getUpstream().getHeartbeatIntervalMs();
        int firstByteMs = properties.getUpstream().getFirstByteTimeoutMs();
        int idleMs = properties.getUpstream().getIdleReadTimeoutMs();
        return Flux.defer(() -> {
            AtomicBoolean terminated = new AtomicBoolean(false);
            AtomicBoolean headersArrived = new AtomicBoolean(false);
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            List<ScheduledFuture<?>> watches = new ArrayList<>(2);

            Flux<String> lines = Flux.create(sink -> {
                DemandBridge bridge = new DemandBridge();

                Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        bridge.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(String line) {
                        lastActivity.set(System.nanoTime());
                        sink.next(line + "\n");
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        cancelWatches(watches);
                        if (terminated.compareAndSet(false, true)) {
                            sink.error(asRuntimeException(throwable));
                        }
                    }

                    @Override
                    public void onComplete() {
                        cancelWatches(watches);
                        if (terminated.compareAndSet(false, true)) {
                            sink.complete();
                        }
                    }
                };

                // Deciding on the status HERE (not in the future callback) prevents
                // a non-2xx body from ever reaching the SSE line subscriber.
                HttpResponse.BodyHandler<String> handler = info -> {
                    headersArrived.set(true);
                    listener.onHeaders(info.statusCode(), info.headers().map());
                    return info.statusCode() >= 300
                            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes())
                            : HttpResponse.BodySubscribers.mapping(
                            HttpResponse.BodySubscribers.fromLineSubscriber(lineSubscriber), ignored -> "");
                };

                CompletableFuture<HttpResponse<String>> future = client.sendAsync(http, handler);

                sink.onRequest(bridge::request);
                Disposable abort = () -> {
                    cancelWatches(watches);
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                    bridge.cancel();
                    if (terminated.compareAndSet(false, true)) {
                        log.debug("Upstream stream cancelled by downstream");
                    }
                };
                // onCancel ONLY. Do NOT also wire onDispose: it fires on normal
                // completion too, and cancel() on a shared keep-alive connection
                // kills sibling requests that are still in flight.
                sink.onCancel(abort);

                future.whenComplete((response, error) -> {
                    // For 2xx the future completes at BODY end; the watches were
                    // already cancelled by the line subscriber's onComplete. This
                    // call is a cheap no-op then; for error statuses it matters.
                    cancelWatches(watches);
                    if (error != null) {
                        if (terminated.compareAndSet(false, true)) {
                            sink.error(asRuntimeException(error));
                        }
                    } else if (response.statusCode() >= 300 && terminated.compareAndSet(false, true)) {
                        sink.error(new UpstreamStatusException(response.statusCode(),
                                response.body(), retryAfterMs(response)));
                    }
                });

                if (firstByteMs > 0 && watchdog != null) {
                    watches.add(watchdog.schedule(() -> {
                        if (!headersArrived.get() && !terminated.get() && !future.isDone()) {
                            boolean first = terminated.compareAndSet(false, true);
                            future.cancel(true);
                            bridge.cancel();
                            if (first) {
                                sink.error(new UpstreamTimeoutException("upstream first-byte timeout"));
                            }
                        }
                    }, firstByteMs, TimeUnit.MILLISECONDS));
                }
                if (idleMs > 0 && watchdog != null) {
                    watches.add(watchdog.scheduleAtFixedRate(() -> {
                        long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs);
                        if (headersArrived.get() && !terminated.get()
                                && System.nanoTime() - lastActivity.get() > idleNanos) {
                            boolean first = terminated.compareAndSet(false, true);
                            future.cancel(true);
                            bridge.cancel();
                            if (first) {
                                sink.error(new UpstreamTimeoutException("upstream idle read timeout"));
                            }
                        }
                    }, idleMs, Math.max(50, idleMs / 2), TimeUnit.MILLISECONDS));
                }
            }, FluxSink.OverflowStrategy.BUFFER);

            if (heartbeatMs <= 0) {
                return lines;
            }
            // Comment frames are transport-level: the engines exclude lines that
            // start with ':' from chunk accounting, TTFT and the first-chunk
            // fallback guard, so this is invisible to the protocol contract.
            return lines.mergeWith(Flux.interval(Duration.ofMillis(heartbeatMs))
                    .map(tick -> HEARTBEAT_FRAME)
                    .takeUntil(frame -> terminated.get()));
        });
    }

    @Override
    public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
        HttpRequest http = build(request, true);
        int heartbeatMs = properties.getUpstream().getHeartbeatIntervalMs();
        int firstByteMs = properties.getUpstream().getFirstByteTimeoutMs();
        int idleMs = properties.getUpstream().getIdleReadTimeoutMs();
        AtomicBoolean headersArrived = new AtomicBoolean(false);
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        List<ScheduledFuture<?>> watches = new ArrayList<>(3);
        // The watchdog can only reach a blocking send/read by interrupting the
        // virtual thread that is blocked on it. The flag converts the resulting
        // InterruptedException/IOException back into the precise timeout reason
        // the reactive engine reports.
        AtomicBoolean watchdogFired = new AtomicBoolean(false);
        AtomicReference<String> timeoutReason = new AtomicReference<>(null);
        Thread caller = Thread.currentThread();
        try {
            if (watchdog != null && firstByteMs > 0) {
                watches.add(watchdog.schedule(() -> {
                    if (!headersArrived.get()) {
                        timeoutReason.set("upstream first-byte timeout");
                        watchdogFired.set(true);
                        caller.interrupt();
                    }
                }, firstByteMs, TimeUnit.MILLISECONDS));
            }
            if (watchdog != null && idleMs > 0) {
                watches.add(watchdog.scheduleAtFixedRate(() -> {
                    long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs);
                    if (headersArrived.get()
                            && System.nanoTime() - lastActivity.get() > idleNanos) {
                        timeoutReason.set("upstream idle read timeout");
                        watchdogFired.set(true);
                        caller.interrupt();
                    }
                }, idleMs, Math.max(50, idleMs / 2), TimeUnit.MILLISECONDS));
            }
            if (watchdog != null && heartbeatMs > 0) {
                watches.add(watchdog.scheduleAtFixedRate(() -> {
                    // StreamSink.write is synchronized; a comment frame written
                    // between upstream chunks is what keeps proxies patient.
                    sink.onChunk(HEARTBEAT_FRAME);
                }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS));
            }

            HttpResponse<Stream<String>> response = client.send(http, HttpResponse.BodyHandlers.ofLines());
            headersArrived.set(true);
            sink.onHeaders(response.statusCode(), response.headers().map());
            // try-with-resources ALWAYS closes the body stream -- including the
            // >=300 branch that used to leak the connection forever.
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() >= 300) {
                    // Drain the error body, then fail. It must NEVER be written
                    // to the client as SSE data lines.
                    String detail = lines.collect(Collectors.joining("\n"));
                    throw new UpstreamStatusException(response.statusCode(), detail,
                            retryAfterMs(response));
                }
                lines.forEach(line -> {
                    lastActivity.set(System.nanoTime());
                    sink.onChunk(line + "\n");
                });
            }
            sink.onDone();
        } catch (IOException e) {
            sink.onError(e);
            throw asTimeoutOrWrapped(watchdogFired, timeoutReason, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.onError(e);
            throw asTimeoutOrWrapped(watchdogFired, timeoutReason, e);
        } catch (RuntimeException e) {
            // The JDK's ofLines stream surfaces a watchdog interrupt as an
            // UncheckedIOException(IOException(InterruptedException)) -- a
            // RuntimeException that would bypass the two catches above and
            // report a bare "InterruptedException" instead of the tier reason.
            sink.onError(e);
            throw asTimeoutOrWrapped(watchdogFired, timeoutReason, e);
        } finally {
            cancelWatches(watches);
            // A watchdog interrupt may land between the last chunk and the
            // finally block; a lingering flag would poison the next request
            // served by this virtual thread, so clear it deliberately.
            Thread.interrupted();
        }
    }

    /** Watchdog interrupts must surface as UpstreamTimeoutException, not a raw wrapped cause. */
    private static IllegalStateException asTimeoutOrWrapped(AtomicBoolean watchdogFired,
                                                            AtomicReference<String> reason,
                                                            Throwable cause) {
        if (watchdogFired.get()) {
            return new UpstreamTimeoutException(reason.get());
        }
        return new IllegalStateException(cause);
    }

    @Override
    public String completeRaw(UpstreamRequest request, HeaderListener listener) {
        HttpRequest http = build(request, false);
        try {
            HttpResponse<String> response = client.send(http,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes()));
            listener.onHeaders(response.statusCode(), response.headers().map());
            return bodyOrThrow(response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Blocking GET (video poll/content). Raw bytes so binaries pass through. */
    @Override
    public byte[] fetchRaw(UpstreamRequest request) {
        HttpRequest http = build(request, false);
        try {
            HttpResponse<byte[]> response = client.send(http, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), properties.getMaxResponseBytes()));
            return bytesOrThrow(response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Non-blocking completion for the reactive data plane.
     * <p>Mono.defer keeps sendAsync LAZY: a cache hit or a cancelled downstream
     * subscription must not issue an upstream request. suppressCancel=false
     * propagates cancellation to the future, but JDK HttpClient does not abort
     * in-flight I/O on cancel, so this is best-effort rather than a hard abort.
     * <p>The HTTP client keeps its dedicated executor on purpose (see the
     * executor-threads sweep note above); the listener fires inside thenApply,
     * which happens-before the Mono emits, so a listener that mutates response
     * headers is safely ordered before the controller writes the body.
     */
    @Override
    public Mono<String> completeRawAsync(UpstreamRequest request, HeaderListener listener) {
        return Mono.defer(() -> {
            HttpRequest http = build(request, false);
            CompletableFuture<String> future = client
                    .sendAsync(http, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes()))
                    .thenApply(response -> {
                        listener.onHeaders(response.statusCode(), response.headers().map());
                        return bodyOrThrow(response);
                    });
            return Mono.fromFuture(future, false)
                    .onErrorMap(JdkHttpUpstream::asRuntimeException);
        });
    }

    /**
     * Async GET for the reactive data plane, mirroring completeRawAsync's
     * laziness contract: no I/O before subscription.
     */
    @Override
    public Mono<byte[]> fetchRawAsync(UpstreamRequest request) {
        return Mono.defer(() -> {
            HttpRequest http = build(request, false);
            CompletableFuture<byte[]> future = client
                    .sendAsync(http, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), properties.getMaxResponseBytes()))
                    .thenApply(JdkHttpUpstream::bytesOrThrow);
            return Mono.fromFuture(future, false)
                    .onErrorMap(JdkHttpUpstream::asRuntimeException);
        });
    }

    /**
     * Keeps the async path's error contract identical to the blocking one:
     * completeRaw wraps IOException in IllegalStateException. Without this,
     * an unwrapped ConnectException (an IOException, not a RuntimeException)
     * would bypass the reactive engine's RuntimeException fallback chain.
     */
    private static Throwable asRuntimeException(Throwable error) {
        Throwable cause = (error instanceof CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null ? error.getCause() : error;
        return cause instanceof RuntimeException ? cause : new IllegalStateException(cause);
    }

    private static String bodyOrThrow(HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw new UpstreamStatusException(response.statusCode(), response.body(),
                    retryAfterMs(response));
        }
        return response.body();
    }

    private static byte[] bytesOrThrow(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 300) {
            throw new UpstreamStatusException(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8), retryAfterMs(response));
        }
        return response.body();
    }

    /**
     * B9: parse an upstream Retry-After into milliseconds. Only the integer-seconds
     * form is honored (the HTTP-date form would need a clock reference the retry
     * planner does not have); unparseable/absent = 0.
     */
    private static long retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::strip)
                .flatMap(v -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(v) * 1000L);
                    } catch (NumberFormatException ignored) {
                        return java.util.Optional.<Long>empty();
                    }
                })
                .orElse(0L);
    }

    private static void cancelWatches(List<ScheduledFuture<?>> watches) {
        for (ScheduledFuture<?> watch : watches) {
            watch.cancel(false);
        }
        watches.clear();
    }

    /**
     * Resolves the upstream URL for a route base + protocol path. A base that
     * already ends with the path is used as-is (pre-v12 configs pointed
     * targetUrl at the full endpoint); otherwise the protocol suffix
     * (/chat/completions, /responses, /messages, /images/generations,
     * /videos/{id}...) is appended.
     */
    private URI targetUri(String base, String path) {
        String key = base + "#" + path;
        URI cached = targetUris.get(key);
        if (cached != null) {
            return cached;
        }
        String suffix = path == null ? "" : path;
        String url = base.endsWith(suffix)
                ? base
                : stripTrailingSlashes(base) + suffix;
        URI uri = URI.create(url);
        targetUris.putIfAbsent(key, uri);
        return uri;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private HttpRequest build(UpstreamRequest request, boolean stream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri(request.targetUrl(), request.path()))
                .timeout(Duration.ofMillis(request.timeoutMs()))
                .header("Accept", stream ? "text/event-stream" : "application/json");
        if (request.method() == UpstreamMethod.GET) {
            builder.GET();
        } else if (request.method() == UpstreamMethod.DELETE) {
            builder.DELETE();
        } else if (request.rawBody() != null) {
            // W4.3 binary body (multipart upload): byte-faithful, no charset
            // round-trip. The caller sets the multipart Content-Type via the
            // header policy pass-through; the gateway never re-encodes it.
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.rawBody()));
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.rawJson(), StandardCharsets.UTF_8));
        }
        String apiKey = request.apiKey();
        boolean keyConfigured = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${");
        if (keyConfigured) {
            io.github.leohou0430.llmgw.core.upstream.UpstreamAuthenticator dialect = authenticators
                    .stream()
                    .filter(a -> a.supports(request.provider()))
                    .findFirst()
                    .orElse(null);
            if (dialect != null) {
                for (Map.Entry<String, String> h : dialect.authHeaders(apiKey).entrySet()) {
                    builder.header(h.getKey(), h.getValue());
                }
            } else {
                // OpenAI-compatible default, unchanged since v1.
                builder.header("Authorization", "Bearer " + apiKey);
            }
        }
        // v13: HeaderPolicy-filtered client headers. UpstreamRequest's compact
        // constructor already stripped the protected names; skip blank values.
        for (Map.Entry<String, String> pass : request.passHeaders().entrySet()) {
            if (pass.getValue() != null && !pass.getValue().isBlank()) {
                builder.header(pass.getKey(), pass.getValue());
            }
        }
        return builder.build();
    }

    /**
     * Bridges downstream Reactor demand to the JDK {@code Flow.Subscription}.
     * onSubscribe and the onRequest callback run on different threads, so both
     * the subscription reference and the pre-subscribe demand live under one
     * lock. An AtomicReference + AtomicLong pair has a lost-demand window
     * between "read null" and "addAndGet", which starves the upstream forever.
     */
    private static final class DemandBridge {
        private final Object lock = new Object();
        private Flow.Subscription subscription;
        private long pending;

        void onSubscribe(Flow.Subscription s) {
            long demand;
            synchronized (lock) {
                subscription = s;
                demand = pending;
                pending = 0;
            }
            if (demand > 0) {
                s.request(demand);
            }
        }

        void request(long n) {
            if (n <= 0) {
                return;
            }
            Flow.Subscription target;
            synchronized (lock) {
                if (subscription == null) {
                    pending = pending > Long.MAX_VALUE - n ? Long.MAX_VALUE : pending + n;
                    return;
                }
                target = subscription;
            }
            target.request(n);
        }

        void cancel() {
            Flow.Subscription target;
            synchronized (lock) {
                target = subscription;
            }
            if (target != null) {
                target.cancel();
            }
        }
    }
}
