package io.github.leohou0430.llmgw.core.upstream;

/** Blocking callback used by the virtual-thread engine. */
public interface ChunkSink {
    void onChunk(String rawSseLine);

    default void onDone() {
    }

    default void onError(Throwable error) {
    }
}
