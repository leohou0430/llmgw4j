package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamProvider;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
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
 */
@Component
public class JdkHttpUpstream implements UpstreamProvider {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpUpstream.class);

    private final HttpClient client;
    private final GatewayProperties properties;
    /** Target URLs come from static route config; build() used to compile a regex
     * and re-parse a URI on EVERY request. Resolve once per distinct URL. */
    private final ConcurrentMap<String, URI> targetUris = new ConcurrentHashMap<>();
    /** Only non-null when we created a dedicated executor (executor-threads > 0). */
    private final java.util.concurrent.ExecutorService ownedExecutor;

    public JdkHttpUpstream(GatewayProperties properties) {
        this.properties = properties;
        int threads = Math.max(0, properties.getUpstream().getExecutorThreads());
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));
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
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    @Override
    public String id() {
        return "jdk-http-" + properties.getUpstream().getType();
    }

    @Override
    public Flux<String> streamRaw(UpstreamRequest request) {
        HttpRequest http = build(request, true);
        return Flux.create(sink -> {
            DemandBridge bridge = new DemandBridge();
            AtomicBoolean terminated = new AtomicBoolean(false);

            Flow.Subscriber<String> lines = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    bridge.onSubscribe(subscription);
                }

                @Override
                public void onNext(String line) {
                    sink.next(line + "\n");
                }

                @Override
                public void onError(Throwable throwable) {
                    if (terminated.compareAndSet(false, true)) {
                        sink.error(asRuntimeException(throwable));
                    }
                }

                @Override
                public void onComplete() {
                    if (terminated.compareAndSet(false, true)) {
                        sink.complete();
                    }
                }
            };

            // Deciding on the status HERE (not in the future callback) prevents
            // a non-2xx body from ever reaching the SSE line subscriber.
            HttpResponse.BodyHandler<String> handler = info -> info.statusCode() >= 300
                    ? HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
                    : HttpResponse.BodySubscribers.mapping(
                            HttpResponse.BodySubscribers.fromLineSubscriber(lines), ignored -> "");

            CompletableFuture<HttpResponse<String>> future = client.sendAsync(http, handler);

            sink.onRequest(bridge::request);
            Disposable abort = () -> {
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
                if (error != null) {
                    if (terminated.compareAndSet(false, true)) {
                        sink.error(asRuntimeException(error));
                    }
                } else if (response.statusCode() >= 300 && terminated.compareAndSet(false, true)) {
                    sink.error(new IllegalStateException(statusMessage(response.statusCode(), response.body())));
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) {
        HttpRequest http = build(request, true);
        try {
            HttpResponse<Stream<String>> response = client.send(http, HttpResponse.BodyHandlers.ofLines());
            // try-with-resources ALWAYS closes the body stream -- including the
            // >=300 branch that used to leak the connection forever.
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() >= 300) {
                    // Drain the error body, then fail. It must NEVER be written
                    // to the client as SSE data lines.
                    String detail = lines.collect(Collectors.joining("\n"));
                    throw new IllegalStateException(statusMessage(response.statusCode(), detail));
                }
                lines.forEach(line -> sink.onChunk(line + "\n"));
            }
            sink.onDone();
        } catch (IOException e) {
            sink.onError(e);
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.onError(e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String completeRaw(UpstreamRequest request) {
        HttpRequest http = build(request, false);
        try {
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            HttpResponse<byte[]> response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
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
     * <p>The HTTP client keeps its default executor on purpose: injecting a
     * virtual-thread executor here would add a second variable to the benchmark.
     */
    @Override
    public Mono<String> completeRawAsync(UpstreamRequest request) {
        return Mono.defer(() -> {
            HttpRequest http = build(request, false);
            CompletableFuture<String> future = client
                    .sendAsync(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(JdkHttpUpstream::bodyOrThrow);
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
                    .sendAsync(http, HttpResponse.BodyHandlers.ofByteArray())
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
            throw new IllegalStateException(statusMessage(response.statusCode(), response.body()));
        }
        return response.body();
    }

    private static byte[] bytesOrThrow(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(statusMessage(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8)));
        }
        return response.body();
    }

    /** 3xx is an error too: this client never follows redirects. */
    private static String statusMessage(int status, String body) {
        String detail = body == null ? "" : body.strip();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300);
        }
        return "upstream status " + status + (detail.isEmpty() ? "" : ": " + detail);
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
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.rawJson(), StandardCharsets.UTF_8));
        }
        String apiKey = request.apiKey();
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${")) {
            builder.header("Authorization", "Bearer " + apiKey);
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
