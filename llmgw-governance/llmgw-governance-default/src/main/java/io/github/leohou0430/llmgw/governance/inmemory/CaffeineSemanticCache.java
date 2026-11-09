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

/** Cache skeleton with a pluggable key function; default is an exact hash key.
 *
 * <p>W3.5: maxSize/TTL come from the shared {@link DynamicGovernanceSettings}.
 * Caffeine cannot resize in place, so a settings-version change atomically
 * swaps in a fresh cache (a one-request cold cache is the cost of retuning;
 * bounded and rare by construction).
 */
public class CaffeineSemanticCache implements SemanticCache {

    private final DynamicGovernanceSettings settings;
    private volatile Cache<String, String> cache;
    private volatile long builtAtVersion;

    public CaffeineSemanticCache(GatewayProperties properties) {
        this(properties, null);
    }

    public CaffeineSemanticCache(GatewayProperties properties, DynamicGovernanceSettings settings) {
        this.settings = settings;
        GovernanceLimits limits = GovernanceLimits.from(properties);
        this.cache = build(limits.cacheMaxSize(), limits.cacheTtlSeconds());
        this.builtAtVersion = settings == null ? 0 : settings.version();
    }

    private static Cache<String, String> build(long maxSize, long ttlSeconds) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    /** Swap the underlying cache when the settings snapshot moved under us. */
    private Cache<String, String> current() {
        if (settings != null && settings.version() != builtAtVersion) {
            DynamicGovernanceSettings.Snapshot snapshot = settings.current();
            cache = build(snapshot.cacheMaxSize(), snapshot.cacheTtlSeconds());
            builtAtVersion = settings.version();
        }
        return cache;
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
        return Optional.ofNullable(current().getIfPresent(key));
    }

    @Override
    public void put(String key, String value) {
        current().put(key, value);
    }

    @Override
    public int size() {
        return (int) current().estimatedSize();
    }

    @Override
    public void invalidateAll() {
        current().invalidateAll();
    }
}
