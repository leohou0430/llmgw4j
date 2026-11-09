package io.github.leohou0430.llmgw.core.upstream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Engine-neutral upstream contract.
 * <p>Each provider offers a reactive stream (WebFlux) and a blocking stream
 * (virtual threads). Chunks are raw SSE lines: the gateway never rebuilds them.
 * <p>The non-streaming path is offered in both flavours for the same reason:
 * reactive data planes MUST call {@link #completeRawAsync(UpstreamRequest, HeaderListener)}
 * so no blocking I/O is offloaded onto an event loop, while servlet data planes
 * call {@link #completeRaw(UpstreamRequest, HeaderListener)} and simply block
 * their own virtual thread. Both engines therefore use the SAME HTTP client,
 * and the benchmark's only remaining variable is the concurrency model itself.
 * <p>v13: every forward carries a {@link HeaderListener} so response headers
 * (x-ratelimit-*, retry-after, request ids) can be observed and forwarded
 * under policy. The single-argument overloads are compatibility bridges for
 * third-party providers that don't care about headers.
 */
public interface UpstreamProvider {

    String id();

    Flux<String> streamRaw(UpstreamRequest request, HeaderListener listener);

    default Flux<String> streamRaw(UpstreamRequest request) {
        return streamRaw(request, HeaderListener.none());
    }

    void streamRawBlocking(UpstreamRequest request, ChunkSink sink);

    String completeRaw(UpstreamRequest request, HeaderListener listener);

    default String completeRaw(UpstreamRequest request) {
        return completeRaw(request, HeaderListener.none());
    }

    /**
     * Truly non-blocking, lazy completion for reactive data planes.
     * Implementations should use an async HTTP client (e.g. HttpClient.sendAsync)
     * and must not start I/O before subscription, otherwise a cache hit or a
     * cancelled request would still hit the upstream.
     * <p>The default is only a compatibility bridge for third-party blocking
     * providers: offloading to boundedElastic is unavoidable there, and is
     * deliberately kept so a blocking implementation can never stall a Netty
     * event loop. JdkHttpUpstream overrides it with a real async path.
     */
    default Mono<String> completeRawAsync(UpstreamRequest request, HeaderListener listener) {
        return Mono.fromCallable(() -> completeRaw(request, listener))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Compatibility bridge for providers that ignore headers. */
    default Mono<String> completeRawAsync(UpstreamRequest request) {
        return completeRawAsync(request, HeaderListener.none());
    }

    /**
     * Blocking GET (video task polling / content download). Returns raw bytes
     * so binary content forwards byte-for-byte; poll bodies are JSON and are
     * stringified by the caller. No fallback is attempted by callers: a task
     * exists only on the upstream that created it.
     */
    default byte[] fetchRaw(UpstreamRequest request) {
        throw new UnsupportedOperationException("provider does not support GET fetches");
    }

    /** Lazy async GET for reactive data planes; same bytes contract. */
    default Mono<byte[]> fetchRawAsync(UpstreamRequest request) {
        return Mono.fromCallable(() -> fetchRaw(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    default void verify() {
        // Optional connectivity check (e.g. via the official SDK).
    }
}
