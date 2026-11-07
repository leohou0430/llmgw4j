package io.github.leohou0430.llmgw.governance.inmemory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/** Cache skeleton with a pluggable key function; default is an exact hash key. */
public class CaffeineSemanticCache implements SemanticCache {

    private final Cache<String, String> cache;

    public CaffeineSemanticCache(GatewayProperties properties) {
        GovernanceLimits limits = GovernanceLimits.from(properties);
        this.cache = Caffeine.newBuilder()
                .maximumSize(limits.cacheMaxSize())
                .expireAfterWrite(Duration.ofSeconds(limits.cacheTtlSeconds()))
                .build();
    }

    public static final EmbeddingKey EXACT_HASH = (model, prompt) -> {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((model + "\n" + prompt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    };

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(String key, String value) {
        cache.put(key, value);
    }

    @Override
    public int size() {
        return (int) cache.estimatedSize();
    }
}
