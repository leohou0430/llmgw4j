package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link KeyStore} used by the app-level control plane (durable SQLite store is a follow-up). */
public final class InMemoryKeyStore implements KeyStore {
    private final Map<String, ApiKeyRegistry.ApiKey> keys = new ConcurrentHashMap<>();

    @Override public void upsert(String token, ApiKeyRegistry.ApiKey key) { keys.put(token, key); }
    @Override public void delete(String token) { keys.remove(token); }
    @Override public void rotate(String oldToken, String newToken, long overlapEnd) { }
    @Override public void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd) { }
    @Override public Map<String, StoredKey> load() { return Map.of(); }
    @Override public boolean enabled() { return true; }
}