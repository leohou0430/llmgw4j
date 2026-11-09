package io.github.leohou0430.llmgw.core.upstream;

import java.util.List;
import java.util.Map;

/**
 * Blocking callback used by the virtual-thread engine.
 */
public interface ChunkSink {

    void onChunk(String rawSseLine);

    /**
     * Fired once when the upstream response headers arrive, before any chunk.
     * Implementations copy the policy-approved names onto the client response
     * while it is still uncommitted. Called on the requesting thread.
     */
    default void onHeaders(int status, Map<String, List<String>> headers) {
    }

    default void onDone() {
    }

    default void onError(Throwable error) {
    }
}
