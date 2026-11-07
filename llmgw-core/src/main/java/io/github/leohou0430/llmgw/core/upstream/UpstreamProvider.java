package io.github.leohou0430.llmgw.core.upstream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Engine-neutral upstream contract.
 * <p>Each provider offers a reactive stream (WebFlux) and a blocking stream
 * (virtual threads). Chunks are raw SSE lines: the gateway never rebuilds them.
 * <p>The non-streaming path is offered in both flavours for the same reason:
 * reactive data planes MUST call {@link #completeRawAsync(UpstreamRequest)} so
 * no blocking I/O is offloaded onto an event loop, while servlet data planes
 * call {@link #completeRaw(UpstreamRequest)} and simply block their own
 * virtual thread. Both engines therefore use the SAME HTTP client, and the
 * benchmark's only remaining variable is the concurrency model itself.
 */
public interface UpstreamProvider {

    String id();

    Flux<String> streamRaw(UpstreamRequest request);

    void streamRawBlocking(UpstreamRequest request, ChunkSink sink);

    String completeRaw(UpstreamRequest request);

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
    default Mono<String> completeRawAsync(UpstreamRequest request) {
        return Mono.fromCallable(() -> completeRaw(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    default void verify() {
        // Optional connectivity check (e.g. via the official SDK).
    }
}
