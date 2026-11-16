package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
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

    private final HttpClient client;
    private final GatewayProperties properties;
    private static final int TARGET_URI_CACHE_LIMIT = 1024;
    /** Fixed admission cache: keep hot endpoints, but never retain every file/task ID. */
    private final ConcurrentMap<String, URI> targetUris = new ConcurrentHashMap<>();
    /** Only non-null when we created a dedicated executor (executor-threads > 0). */
    private final java.util.concurrent.ExecutorService ownedExecutor;
    /** One lazy deadline scheduler per transport; blocking heartbeats stay on the requesting thread. */
    private final ScheduledThreadPoolExecutor watchdog;
    /** Only terminal timeout work uses these per-operation virtual threads. */
    private final java.util.concurrent.ExecutorService timeoutSignals = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("llmgw-upstream-timeout-", 0).factory());
    private final Map<String, io.github.leohou0430.llmgw.provider.UpstreamAuthenticator> authenticators;

    public JdkHttpUpstream(GatewayProperties properties) {
        this.properties = properties;
        var loader = io.github.leohou0430.llmgw.core.extension.Extensions.load(
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
        this.watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "llmgw-upstream-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.watchdog.setRemoveOnCancelPolicy(true);
    }

    @Override public void close() {
        client.close();
        watchdog.shutdownNow();
        // Drain dispatches before retiring their executor; never await a user callback here.
        watchdog.close();
        timeoutSignals.shutdownNow();
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
        private final AtomicBoolean linesCompleted = new AtomicBoolean();
        private final AtomicBoolean httpCompleted = new AtomicBoolean();
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
            CompletableFuture<?> active = future.get();
            // A queued timeout may run after HTTP completion and connection reuse.
            if (active != null && active.isDone() && !active.isCompletedExceptionally()) return;
            demand.cancel();
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

        private void completeIfReady() {
            if (linesCompleted.get() && httpCompleted.get()) complete();
        }

        private void timeout(Throwable error) {
            if (!terminated.compareAndSet(false, true)) return;
            cancelWatches(watches);
            timeoutSignals.execute(() -> {
                abortTransport();
                synchronized (signals) { downstream.onError(asRuntimeException(error)); }
            });
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
                watch(watchdog.schedule(() -> timeout(new UpstreamTimeoutException("upstream request timeout")),
                        Math.max(1, request.timeoutMs()), TimeUnit.MILLISECONDS));
                var lines = new Flow.Subscriber<String>() {
                    @Override public void onSubscribe(Flow.Subscription s) { demand.onSubscribe(s); }
                    @Override public void onNext(String line) { next(line); }
                    // The HTTP future delivers terminal signals after body completion.
                    @Override public void onError(Throwable error) {}
                    @Override public void onComplete() { linesCompleted.set(true); completeIfReady(); }
                };
                int idleMs = properties.getUpstream().getIdleReadTimeoutMs();
                HttpResponse.BodyHandler<String> handler = info -> {
                    lastActivity.set(System.nanoTime());
                    headersArrived.set(true);
                    listener.onHeaders(info.statusCode(), info.headers().map());
                    HttpResponse.BodySubscriber<String> body = info.statusCode() >= 300
                            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes())
                            : new SseLineLimitSubscriber<>(HttpResponse.BodySubscribers.mapping(
                                    new SseLineDecoder(lines), ignored -> ""),
                                    properties.getUpstream().getMaxSseLineBytes());
                    return idleMs > 0 ? new ReadActivitySubscriber<>(body, lastActivity) : body;
                };
                var active = client.sendAsync(http, handler);
                future.set(active);
                if (aborted.get() && !active.isDone()) active.cancel(true);
                active.whenComplete((response, error) -> {
                    cancelWatches(watches);
                    if (error != null) fail(error, false);
                    else if (response.statusCode() >= 300) fail(new UpstreamStatusException(
                            response.statusCode(), response.body(), retryAfterMs(response)), false);
                    else { httpCompleted.set(true); completeIfReady(); }
                });
                int firstByteMs = properties.getUpstream().getFirstByteTimeoutMs();
                if (firstByteMs > 0 && watchdog != null) {
                    watch(watchdog.schedule(() -> {
                        if (!headersArrived.get()) timeout(new UpstreamTimeoutException("upstream first-byte timeout"));
                    }, firstByteMs, TimeUnit.MILLISECONDS));
                }
                if (idleMs > 0 && watchdog != null) {
                    watch(watchdog.scheduleAtFixedRate(() -> {
                        if (headersArrived.get() && System.nanoTime() - lastActivity.get() > TimeUnit.MILLISECONDS.toNanos(idleMs)) {
                            timeout(new UpstreamTimeoutException("upstream idle read timeout"));
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
        var deadline = new BlockingDeadline(request.timeoutMs());
        try (deadline) {
            if (watchdog != null && firstByteMs > 0) {
                deadline.watch(watchdog.schedule(() -> {
                    if (!headersArrived.get()) {
                        deadline.expire("upstream first-byte timeout");
                    }
                }, firstByteMs, TimeUnit.MILLISECONDS));
            }
            if (watchdog != null && idleMs > 0) {
                deadline.watch(watchdog.scheduleAtFixedRate(() -> {
                    long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs);
                    if (headersArrived.get()
                            && System.nanoTime() - lastActivity.get() > idleNanos) {
                        deadline.expire("upstream idle read timeout");
                    }
                }, idleMs, Math.max(50, idleMs / 2), TimeUnit.MILLISECONDS));
            }
            HttpResponse<Stream<String>> response = client.send(http, info -> {
                lastActivity.set(System.nanoTime());
                headersArrived.set(true);
                HttpResponse.BodySubscriber<Stream<String>> body = info.statusCode() >= 300
                        ? HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.limiting(
                                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes()), Stream::of)
                        : new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(heartbeatMs, sink),
                                properties.getUpstream().getMaxSseLineBytes());
                return idleMs > 0 ? new ReadActivitySubscriber<>(body, lastActivity) : body;
            });
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
            throw deadline.failure(e);
        } catch (InterruptedException e) {
            if (!deadline.expired()) Thread.currentThread().interrupt();
            sink.onError(e);
            throw deadline.failure(e);
        } catch (RuntimeException e) {
            // The blocking line reader surfaces interruption through a runtime
            // exception; preserve the watchdog's tier-specific timeout reason.
            sink.onError(e);
            throw deadline.failure(e);
        }
    }

    /** Prevent late watchdog interrupts from escaping into the caller's next operation. */
    private final class BlockingDeadline implements AutoCloseable {
        private final Thread caller = Thread.currentThread();
        private final List<ScheduledFuture<?>> tasks = new ArrayList<>(3);
        private String reason;
        private boolean closed;
        BlockingDeadline(int timeoutMs) {
            watch(watchdog.schedule(() -> expire("upstream request timeout"), Math.max(1, timeoutMs), TimeUnit.MILLISECONDS));
        }
        synchronized void watch(ScheduledFuture<?> task) {
            if (closed) task.cancel(false); else tasks.add(task);
        }
        synchronized void expire(String message) {
            if (!closed && reason == null) { reason = message;caller.interrupt(); }
        }
        synchronized boolean expired() { return reason != null; }
        synchronized IllegalStateException failure(Throwable error) {
            return reason != null ? new UpstreamTimeoutException(reason) : new IllegalStateException(error);
        }
        @Override public synchronized void close() {
            closed = true;
            for (var task : tasks) task.cancel(false);
            tasks.clear();
            if (reason != null) Thread.interrupted();
        }
    }

    private <T> HttpResponse<T> sendBlocking(HttpRequest request, HttpResponse.BodyHandler<T> handler, int timeoutMs) {
        var deadline = new BlockingDeadline(timeoutMs);
        try (deadline) {
            return client.send(request, handler);
        } catch (IOException error) {
            throw deadline.failure(error);
        } catch (InterruptedException error) {
            if (!deadline.expired()) Thread.currentThread().interrupt();
            throw deadline.failure(error);
        }
    }

    @Override
    public String completeRaw(UpstreamRequest request, HeaderListener listener) {
        HttpResponse<String> response = sendBlocking(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes()), request.timeoutMs());
        listener.onHeaders(response.statusCode(), response.headers().map());
        return bodyOrThrow(response);
    }

    /** Blocking GET: bounded bytes, with a deadline that includes the response body. */
    @Override
    public byte[] fetchRaw(UpstreamRequest request) {
        return bytesOrThrow(sendBlocking(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), properties.getMaxResponseBytes()), request.timeoutMs()));
    }

    /** Native asynchronous operations start on invocation; adapters defer invocation until subscription. */
    @Override public CompletableFuture<String> completeRawAsync(UpstreamRequest request, HeaderListener listener) {
        return mapResponse(withDeadline(client.sendAsync(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), properties.getMaxResponseBytes())), request.timeoutMs()), response -> {
            listener.onHeaders(response.statusCode(), response.headers().map());
            return bodyOrThrow(response);
        });
    }

    @Override public CompletableFuture<byte[]> fetchRawAsync(UpstreamRequest request) {
        return mapResponse(withDeadline(client.sendAsync(build(request, false), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), properties.getMaxResponseBytes())), request.timeoutMs()), JdkHttpUpstream::bytesOrThrow);
    }

    private static final int DOWNLOAD_CHUNK_BYTES = 16 * 1024;

    /**
     * T2.1/T2.2 streaming download. Cold: the exchange starts on first demand, not on
     * call. {@code BodyHandlers.ofInputStream()} is a BodySubscribers-backed streaming
     * handler — the body is read in bounded chunks, never buffered whole. Cumulative
     * bytes are capped at maxResponseBytes (exceed closes the upstream and errors);
     * cancel/close/EOF/error all release the response InputStream exactly once.
     * T2.8: each blocking body read is bounded by the idle-read timeout — a
     * stalled upstream closes the stream and surfaces {@code upstream idle read
     * timeout}; the request timeout still bounds the exchange start.
     */
    @Override
    public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest request) {
        return ByteStream.of(new ByteStream.Source() {
            private volatile InputStream body;
            private final AtomicBoolean closed = new AtomicBoolean();
            private volatile boolean idleTimedOut;
            private boolean started;
            private long counted;

            private void start() {
                if (started) {
                    return;
                }
                started = true;
                HttpResponse<InputStream> response = sendBlocking(build(request, false),
                        HttpResponse.BodyHandlers.ofInputStream(), request.timeoutMs());
                if (response.statusCode() >= 300) {
                    InputStream err = response.body();
                    byte[] msg;
                    try {
                        msg = err.readNBytes((int) Math.min(properties.getMaxResponseBytes(), 64 * 1024));
                    } catch (IOException e) {
                        msg = new byte[0];
                    } finally {
                        closeQuietly(err);
                    }
                    throw new UpstreamStatusException(response.statusCode(),
                            new String(msg, StandardCharsets.UTF_8), retryAfterMs(response));
                }
                body = response.body();
            }

            @Override
            public ByteChunk next() throws Exception {
                start();
                byte[] buf = new byte[DOWNLOAD_CHUNK_BYTES];
                int idleMs = properties.getUpstream().getIdleReadTimeoutMs();
                var readDone = new AtomicBoolean();
                ScheduledFuture<?> idleTimer = null;
                if (idleMs > 0) {
                    idleTimer = watchdog.schedule(() -> {
                        if (readDone.compareAndSet(false, true)) {
                            idleTimedOut = true;
                            close();
                        }
                    }, idleMs, TimeUnit.MILLISECONDS);
                }
                int n;
                try {
                    n = body.read(buf);
                } catch (IOException e) {
                    if (idleTimedOut) {
                        throw new UpstreamTimeoutException("upstream idle read timeout");
                    }
                    throw e;
                } finally {
                    readDone.set(true);
                    if (idleTimer != null) {
                        idleTimer.cancel(false);
                    }
                }
                if (idleTimedOut) {
                    throw new UpstreamTimeoutException("upstream idle read timeout");
                }
                if (n < 0) {
                    return null;
                }
                counted += n;
                if (counted > properties.getMaxResponseBytes()) {
                    close();
                    throw new IOException("upstream response exceeds maxResponseBytes");
                }
                return new ByteChunk(ByteBuffer.wrap(buf, 0, n));
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    closeQuietly(body);
                }
            }
        });
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // releasing the response body; nothing actionable on close failure
            }
        }
    }

    private <T> CompletableFuture<T> withDeadline(CompletableFuture<T> source, int timeoutMs) {
        var decided = new AtomicBoolean();
        var timerRef = new AtomicReference<ScheduledFuture<?>>();
        var result = new LinkedFuture<T>(source, () -> {
            decided.set(true);
            ScheduledFuture<?> timer = timerRef.get();
            if (timer != null) timer.cancel(false);
        });
        var timer = watchdog.schedule(() -> {
            if (decided.compareAndSet(false, true)) timeoutSignals.execute(() -> {
                source.cancel(true);
                result.completeExceptionally(new UpstreamTimeoutException("upstream request timeout"));
            });
        }, Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        timerRef.set(timer);
        source.whenComplete((response, error) -> {
            timer.cancel(false);
            if (!decided.compareAndSet(false, true)) return;
            if (error == null) result.complete(response); else result.completeExceptionally(error);
        });
        return result;
    }

    private static <T,R> CompletableFuture<R> mapResponse(CompletableFuture<T> source, java.util.function.Function<T,R> mapper) {
        var result = new LinkedFuture<R>(source, null);
        source.whenComplete((response, error) -> {
            if (result.cancelling) return;
            if (error != null) result.completeExceptionally(asRuntimeException(error));
            else {
                try { result.complete(mapper.apply(response)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }
        });
        return result;
    }

    /** Abort the private parent before CompletableFuture runs caller cancellation callbacks. */
    private static final class LinkedFuture<T> extends CompletableFuture<T> {
        private final Object cancellation = new Object();
        private volatile CompletableFuture<?> source;
        private volatile Runnable beforeCancel;
        private volatile boolean cancelling;
        LinkedFuture(CompletableFuture<?> source, Runnable beforeCancel) {
            this.source = source;
            this.beforeCancel = beforeCancel;
        }
        @Override public boolean cancel(boolean interrupt) {
            synchronized (cancellation) {
                if (isDone()) return isCancelled();
                cancelling = true;
                Runnable prepare = beforeCancel;
                CompletableFuture<?> parent = source;
                beforeCancel = null; source = null;
                if (prepare != null) prepare.run();
                if (parent != null) parent.cancel(true);
            }
            return super.cancel(interrupt);
        }
        @Override public boolean complete(T value) {
            beforeCancel = null; source = null;
            return super.complete(value);
        }
        @Override public boolean completeExceptionally(Throwable error) {
            beforeCancel = null; source = null;
            return super.completeExceptionally(error);
        }
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
        if (targetUris.size() < TARGET_URI_CACHE_LIMIT) {
            synchronized (targetUris) {
                if (targetUris.size() < TARGET_URI_CACHE_LIMIT) targetUris.putIfAbsent(key, uri);
            }
        }
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
        } else if (request.body() != null) {
            // T2.1/T2.2 binary body (multipart upload): streamed byte-faithful via
            // the native ByteStream contract (BodyPublishers.fromPublisher), never
            // a whole-byte[] publisher. The caller sets the multipart Content-Type
            // via the header policy pass-through; the gateway never re-encodes it.
            builder.POST(HttpRequest.BodyPublishers.fromPublisher(request.body(), request.bodyLength()));
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
                builder.setHeader(pass.getKey(), pass.getValue());
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
