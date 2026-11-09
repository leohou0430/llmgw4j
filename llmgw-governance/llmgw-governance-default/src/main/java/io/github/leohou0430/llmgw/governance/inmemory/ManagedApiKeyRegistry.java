package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyAdminService;
import io.github.leohou0430.llmgw.core.governance.KeyStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link KeyAdminService} on top of the in-memory registry: runtime
 * create/revoke with TTL expiry and {@link KeyStore} persistence, so keys
 * created via {@code POST /admin/keys} survive a restart (config keys are
 * static source and re-register at boot as before).
 *
 * <p>B1 at-rest model: the in-memory map is keyed by the AT-REST key
 * ({@code lookupKey(token)} — hash when {@code hash-keys-at-rest}, plaintext
 * otherwise), the store is keyed the same way, and plaintext tokens are held
 * only for the create/rotate response and store write. Rotation keeps the old
 * token authenticating for an overlap window via {@code legacyTokens}.
 *
 * <p>Hot path unchanged: {@code find} is one map lookup plus expiry filter.
 */
public class ManagedApiKeyRegistry extends InMemoryApiKeyRegistry implements KeyAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** key id -> AT-REST key (the plaintext is not retained). */
    private final Map<String, String> idToAtRest = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final KeyStore store;
    private final GatewayProperties properties;
    /** B1: rotated-out tokens still valid until their overlap window closes. */
    private final Map<String, ApiKey> legacyTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> legacyUntil = new ConcurrentHashMap<>();

    public ManagedApiKeyRegistry(GatewayProperties properties, JsonKeyStore store) {
        this(properties, (KeyStore) store);
    }

    public ManagedApiKeyRegistry(GatewayProperties properties, KeyStore store) {
        super(properties);
        this.properties = properties;
        this.store = store;
        // Restore: store entries are keyed by their AT-REST key already, so
        // they drop straight into byToken. Rotated-out entries with an
        // unexpired overlap window re-register as legacy authenticators.
        store.load().forEach((atRest, stored) -> {
            byToken.put(atRest, stored.key());
            idToAtRest.put(stored.key().id(), atRest);
            if (stored.rotatedTo() != null && stored.legacyUntil() > 0) {
                legacyTokens.put(atRest, stored.key());
                legacyUntil.put(atRest, stored.legacyUntil());
            }
        });
    }

    @Override
    public CreatedKey create(String name, long quotaTokens, Set<String> allowedModels,
                             Long ttlSeconds) {
        return create(name, "", 0, quotaTokens, allowedModels, ttlSeconds, Map.of());
    }

    @Override
    public CreatedKey create(String name, String group, long budgetMicroUsd, long quotaTokens,
                             Set<String> allowedModels, Long ttlSeconds) {
        return create(name, group, budgetMicroUsd, quotaTokens, allowedModels, ttlSeconds, Map.of());
    }

    @Override
    public CreatedKey create(String name, String group, long budgetMicroUsd, long quotaTokens,
                             Set<String> allowedModels, Long ttlSeconds,
                             Map<String, String> metadata) {
        String token = "sk-" + HEX.formatHex(randomBytes(24));
        String id = "key-" + sequence.incrementAndGet();
        long expiresAt = ttlSeconds == null || ttlSeconds <= 0
                ? 0
                : System.currentTimeMillis() + ttlSeconds * 1000;
        ApiKey key = new ApiKey(id, name == null || name.isBlank() ? id : name,
                quotaTokens, Set.copyOf(allowedModels), expiresAt,
                group == null ? "" : group, Math.max(0, budgetMicroUsd),
                metadata == null ? Map.of() : Map.copyOf(metadata));
        String atRest = lookupKey(token);
        byToken.put(atRest, key);
        idToAtRest.put(id, atRest);
        store.upsert(token, key);
        return new CreatedKey(key, token);
    }

    @Override
    public CreatedKey rotate(String keyId, int overlapSeconds) {
        String atRestOld = idToAtRest.get(keyId);
        ApiKey key = atRestOld == null ? null : byToken.get(atRestOld);
        if (key == null) {
            throw new IllegalArgumentException("unknown key id " + keyId);
        }
        String newToken = "sk-" + HEX.formatHex(randomBytes(24));
        String atRestNew = lookupKey(newToken);
        // The old token LOSES its direct byToken entry: authentication now
        // flows through the legacy overlap map until the window closes.
        byToken.remove(atRestOld);
        byToken.put(atRestNew, key);
        idToAtRest.put(keyId, atRestNew);
        long overlapEnd = System.currentTimeMillis()
                + Math.max(0, overlapSeconds) * 1000L;
        legacyTokens.put(atRestOld, key);
        legacyUntil.put(atRestOld, overlapEnd);
        store.rotateAtRest(atRestOld, atRestNew, overlapEnd);
        store.upsert(newToken, key);
        return new CreatedKey(key, newToken);
    }

    @Override
    public boolean revoke(String keyId) {
        String atRest = idToAtRest.get(keyId);
        if (atRest == null) {
            return false;
        }
        ApiKey removed = byToken.remove(atRest);
        idToAtRest.remove(keyId);
        if (removed == null) {
            return false;
        }
        store.deleteAtRest(atRest);
        return true;
    }

    @Override
    public Optional<ApiKey> byId(String keyId) {
        String atRest = idToAtRest.get(keyId);
        return atRest == null ? Optional.empty() : Optional.ofNullable(byToken.get(atRest));
    }

    @Override
    public Optional<ApiKey> find(String token) {
        Optional<ApiKey> direct = super.find(token)
                .filter(key -> !key.expired(System.currentTimeMillis()));
        if (direct.isPresent()) {
            return direct;
        }
        // B1: rotated-out token inside its overlap window still authenticates.
        String atRest = lookupKey(token);
        Long until = legacyUntil.get(atRest);
        ApiKey legacy = legacyTokens.get(atRest);
        if (until != null && legacy != null) {
            if (System.currentTimeMillis() < until) {
                return Optional.of(legacy);
            }
            legacyTokens.remove(atRest);
            legacyUntil.remove(atRest);
        }
        return Optional.empty();
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
