package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryApiKeyRegistry implements ApiKeyRegistry {

    /** Protected for {@link ManagedApiKeyRegistry}: same map, more lifecycle.
     *  B1: keyed by {@link #lookupKey(String)} — hash(token) when
     *  gateway.auth.hash-keys-at-rest is on, plaintext otherwise. */
    protected final Map<String, ApiKey> byToken = new ConcurrentHashMap<>();
    private final boolean hashLookup;

    public InMemoryApiKeyRegistry(GatewayProperties properties) {
        this.hashLookup = properties.getAuth().isHashKeysAtRest();
        if (properties.getApiKeys().isEmpty()) {
            byToken.put(lookupKey("sk-demo"), new ApiKey("demo", "demo",
                    GovernanceLimits.from(properties).defaultQuotaTokens(), Set.of()));
        } else {
            for (GatewayProperties.ApiKeyDef def : properties.getApiKeys()) {
                byToken.put(lookupKey(def.getToken()), new ApiKey(def.getId(), def.getName(),
                        def.getQuotaTokens(), Set.copyOf(def.getAllowedModels()),
                        0, def.getGroup(), def.getBudgetMicroUsd(),
                        Map.copyOf(def.getMetadata())));
            }
        }
    }

    /** B1: the in-memory lookup key for a presented plaintext token. */
    protected String lookupKey(String token) {
        if (!hashLookup) {
            return token;
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void register(String token, ApiKey key) {
        byToken.put(lookupKey(token), key);
    }

    @Override
    public Optional<ApiKey> find(String token) {
        return Optional.ofNullable(byToken.get(lookupKey(token)));
    }

    @Override
    public Collection<ApiKey> all() {
        return List.copyOf(byToken.values());
    }
}
