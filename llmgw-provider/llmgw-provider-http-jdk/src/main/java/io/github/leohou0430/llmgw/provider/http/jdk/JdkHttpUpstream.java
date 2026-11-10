package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Native JDK HTTP transport shared by both engines. Streaming subscriptions own
 * their demand, cancellation and watchdogs. Normal completion never cancels
 * the body/future, which would tear down a keep-alive connection reused by siblings.
 * WebFlux adapts Flow/Future and adds transport heartbeat comments at its edge;
 * the blocking VT path writes heartbeat comments through its ChunkSink.
 */
public class JdkHttpUpstream implements UpstreamProvider, AutoCloseable {

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
    private final Map<String, io.github.leohou0430.llmgw.provider.UpstreamAuthenticator> authenticators;

    public JdkHttpUpstream(GatewayProperties properties) {
        this.properties = properties;
        var loader = org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader(
                io.github.leohou0430.llmgw.provider.UpstreamAuthenticator.class);
        Map<String, io.github.leohou0430.llmgw.provider.UpstreamAuthenticator> discovered = new java.util.LinkedHashMap<>();
        for (String name : loader.getSupportedExtensions()) discovered.put(name, loader.getExtension(name));
        this.authenticators = Map.copyOf(discovered);
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

    @Override public void close() {
        client.close();
        if (watchdog != null) watchdog.shutdownNow();
        if (ownedExecutor != null) ownedExecutor.close();
    }

    @Override
    public String id() {
        return "jdk-http-" + properties.getUpstream().getType();
    }

    @Override
    public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
        return subscriber -> {
            java.util.Objects.requireNonNull(subscriber);
            var subscription = new LineSubscription(subscriber, listener);
            subscriber.onSubscribe(subscription);
            subscription.start(request);
        };
    }

    /** Native demand bridge: only explicit cancel/timeouts abort transport, never normal completion. */
    private final class LineSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super String> downstream;
        private final HeaderListener listener;
        private final DemandBridge demand = new DemandBridge();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean headersArrived = new AtomicBoolean();
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
        private final Object signals = new Object();
        private final List<ScheduledFuture<?>> watches = new ArrayList<>(2);

        private LineSubscription(Flow.Subscriber<? super String> downstream, HeaderListener listener) {
            this.downstream = downstream;
            this.listener = listener;
        }

        @Override public void request(long n) {
            if (n <= 0) {
                fail(new IllegalArgumentException("Flow demand must be positive"), true);
            } else if (!terminated.get()) {
                demand.request(n);
            }
        }

        @Override public void cancel() {
            if (terminated.compareAndSet(false, true)) {
                cancelWatches(watches);
                abortTransport();
            }
        }

        private void abortTransport() {
            aborted.set(true);
            demand.cancel();
            CompletableFuture<?> active = future.get();
            if (active != null && !active.isDone()) active.cancel(true);
        }

        private void fail(Throwable error, boolean abort) {
            if (!terminated.compareAndSet(false, true)) return;
            cancelWatches(watches);
            if (abort) abortTransport();
            synchronized (signals) { downstream.onError(asRuntimeException(error)); }
        }

        private void complete() {
            if (!terminated.compareAndSet(false, true)) return;
            cancelWatches(watches);
            synchronized (signals) { downstream.onComplete(); }
        }

        private void next(String line) {
            Throwable broken = null;
            synchronized (signals) {
                if (terminated.get()) return;
                lastActivity.set(System.nanoTime());
                try { downstream.onNext(line + "\n"); }
                catch (Throwable failure) { broken = failure; }
            }
            if (broken != null) {
                cancel();
                if (broken instanceof Error fatal) throw fatal;
            }
        }

        private void watch(ScheduledFuture<?> task) {
            synchronized (watches) {
                if (terminated.get()) task.cancel(false);
                else watches.add(task);
            }
        }

        private void start(UpstreamRequest request) {
            if (terminated.get()) return;
            try {
                HttpRequest http = build(request, true);
                var lines = new Flow.Subscriber<String>() {
                    @Override public void onSubscribe(Flow.Subscription s) { demand.onSubscribe(s); }
                    @Override public void onNext(String line) { next(line); }
                    @Override public void onError(Throwable error) { fail(error, false); }
                    @Override public void onComplete() { complete(); }
                };
                HttpResponse.BodyHandler<String> handler = info -> {
                    headersArrived.set(true);
                    listener.onHeaders(info.statusCode(), info.headers().map());
                    return info.statusCode() >= 300
                            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes())
                            : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.fromLineSubscriber(lines), ignored -> "");
                };
                var active = client.sendAsync(http, handler);
                future.set(active);
                if (aborted.get() && !active.isDone()) active.cancel(true);
                active.whenComplete((response, error) -> {
                    cancelWatches(watches);
                    if (error != null) fail(error, false);
                    else if (response.statusCode() >= 300) fail(new UpstreamStatusException(
                            response.statusCode(), response.body(), retryAfterMs(response)), false);
                });
                int firstByteMs = properties.getUpstream().getFirstByteTimeoutMs();
                if (firstByteMs > 0 && watchdog != null) {
                    watch(watchdog.schedule(() -> {
                        if (!headersArrived.get()) fail(new UpstreamTimeoutException("upstream first-byte timeout"), true);
                    }, firstByteMs, TimeUnit.MILLISECONDS));
                }
                int idleMs = properties.getUpstream().getIdleReadTimeoutMs();
                if (idleMs > 0 && watchdog != null) {
                    watch(watchdog.scheduleAtFixedRate(() -> {
                        if (headersArrived.get() && System.nanoTime() - lastActivity.get() > TimeUnit.MILLISECONDS.toNanos(idleMs)) {
                            fail(new UpstreamTimeoutException("upstream idle read timeout"), true);
                        }
                    }, idleMs, Math.max(50, idleMs / 2), TimeUnit.MILLISECONDS));
                }
            } catch (RuntimeException failure) {
                fail(failure, true);
            }
        }
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

    /** Native asynchronous operations start on invocation; adapters defer invocation until subscription. */
    @Override public CompletableFuture<String> completeRawAsync(UpstreamRequest request, HeaderListener listener) {
        return mapResponse(client.sendAsync(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes())), response -> {
            listener.onHeaders(response.statusCode(), response.headers().map());
            return bodyOrThrow(response);
        });
    }

    @Override public CompletableFuture<byte[]> fetchRawAsync(UpstreamRequest request) {
        return mapResponse(client.sendAsync(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), properties.getMaxResponseBytes())), JdkHttpUpstream::bytesOrThrow);
    }

    private static <T,R> CompletableFuture<R> mapResponse(CompletableFuture<T> source, java.util.function.Function<T,R> mapper) {
        var result = new CompletableFuture<R>();
        source.whenComplete((response, error) -> {
            if (error != null) result.completeExceptionally(asRuntimeException(error));
            else {
                try { result.complete(mapper.apply(response)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) source.cancel(true); });
        return result;
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
        synchronized (watches) {
            for (ScheduledFuture<?> watch : watches) watch.cancel(false);
            watches.clear();
        }
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
        String providerName = request.provider() == null || request.provider().isBlank()
                ? "openai" : request.provider().trim().toLowerCase(java.util.Locale.ROOT);
        if (request.provider() != null && !request.provider().isBlank() && !authenticators.containsKey(providerName)) {
            throw new IllegalArgumentException("unknown upstream provider: " + providerName);
        }
        if (keyConfigured) {
            var dialect = authenticators.get(providerName);
            if (dialect == null) throw new IllegalArgumentException("unknown upstream provider: " + providerName);
            for (Map.Entry<String, String> header : dialect.authHeaders(apiKey).entrySet()) {
                builder.header(header.getKey(), header.getValue());
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
        private boolean cancelled;

        void onSubscribe(Flow.Subscription s) {
            long demand;
            synchronized (lock) {
                if (cancelled || subscription != null) { s.cancel();return; }
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
                if (cancelled) return;
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
                cancelled = true;
                pending = 0;
                target = subscription;
            }
            if (target != null) {
                target.cancel();
            }
        }
    }
}
