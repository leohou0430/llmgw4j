package io.github.leohou0430.llmgw.provider;

import io.github.leohou0430.llmgw.core.upstream.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Engine-neutral upstream contract: JDK Flow/Future, with no Reactor or Spring types. */
public interface UpstreamProvider {
    String id();
    /** Cold publisher: each subscription owns its request, demand and cancellation. */
    Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener);
    default Flow.Publisher<String> streamRaw(UpstreamRequest request) { return streamRaw(request, HeaderListener.none()); }
    void streamRawBlocking(UpstreamRequest request, ChunkSink sink);
    String completeRaw(UpstreamRequest request, HeaderListener listener);
    default String completeRaw(UpstreamRequest request) { return completeRaw(request, HeaderListener.none()); }
    default CompletableFuture<String> completeRawAsync(UpstreamRequest request, HeaderListener listener) {
        return submit(() -> completeRaw(request, listener));
    }
    default CompletableFuture<String> completeRawAsync(UpstreamRequest request) { return completeRawAsync(request, HeaderListener.none()); }
    default byte[] fetchRaw(UpstreamRequest request) { throw new UnsupportedOperationException("provider does not support GET fetches"); }
    default CompletableFuture<byte[]> fetchRawAsync(UpstreamRequest request) { return submit(() -> fetchRaw(request)); }
    default void verify() {}

    /** Safe fallback for custom blocking providers; the JDK HTTP implementation overrides with sendAsync. */
    private static <T> CompletableFuture<T> submit(Callable<T> work) {
        var result = new CompletableFuture<T>();
        var thread = Thread.ofVirtual().unstarted(() -> {
            try { result.complete(work.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) thread.interrupt(); });
        thread.start();
        return result;
    }
}
