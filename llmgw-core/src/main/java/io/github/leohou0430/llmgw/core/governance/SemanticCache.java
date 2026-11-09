package io.github.leohou0430.llmgw.core.governance;

import java.util.Optional;

/** Semantic cache skeleton: key generation is pluggable, storage is in-memory only. */
public interface SemanticCache {

    interface EmbeddingKey {
        String of(String model, String prompt);
    }

    Optional<String> get(String key);

    void put(String key, String value);

    int size();

    /**
     * B8: drop every cached entry (admin cache-invalidation endpoint).
     * No-op by default for stores that cannot enumerate their keys.
     */
    default void invalidateAll() {
    }
}
