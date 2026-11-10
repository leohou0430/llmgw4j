package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyAdminService;
import io.github.leohou0430.llmgw.core.governance.KeyStore;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final KeyStore store;
    /** B1: rotated-out tokens still valid until their overlap window closes. */
    private final Map<String, ApiKey> legacyTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> legacyUntil = new ConcurrentHashMap<>();

    public ManagedApiKeyRegistry(GatewayProperties properties, JsonKeyStore store) {
        this(properties, (KeyStore) store);
    }

    public ManagedApiKeyRegistry(GatewayProperties properties, KeyStore store) {
        super(properties);
        this.store = store;
        var currentIdentities = new java.util.HashMap<String, String>();
        byToken.forEach((token, key) -> currentIdentities.put(key.id(), token));
        store.load().forEach((atRest, stored) -> {
            if (stored.rotatedTo() != null) {
                if (stored.legacyUntil() > System.currentTimeMillis()) {
                    legacyTokens.put(atRest, stored.key());
                    legacyUntil.put(atRest, stored.legacyUntil());
                }
            } else {
                String existing = currentIdentities.putIfAbsent(stored.key().id(), atRest);
                if (existing != null && !existing.equals(atRest)) {
                    throw new IllegalStateException("ambiguous current key identity: " + stored.key().id());
                }
                byToken.put(atRest, stored.key());
                idToAtRest.put(stored.key().id(), atRest);
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
    public synchronized CreatedKey create(String name, String group, long budgetMicroUsd, long quotaTokens,
                             Set<String> allowedModels, Long ttlSeconds,
                             Map<String, String> metadata) {
        String token = "sk-" + HEX.formatHex(randomBytes(24));
        String id = "key-" + HEX.formatHex(randomBytes(16));
        long expiresAt = ttlSeconds == null || ttlSeconds <= 0
                ? 0
                : System.currentTimeMillis() + ttlSeconds * 1000;
        ApiKey key = new ApiKey(id, name == null || name.isBlank() ? id : name,
                quotaTokens, Set.copyOf(allowedModels), expiresAt,
                group == null ? "" : group, Math.max(0, budgetMicroUsd),
                metadata == null ? Map.of() : Map.copyOf(metadata));
        String atRest = lookupKey(token);
        store.upsert(token, key);
        byToken.put(atRest, key);
        idToAtRest.put(id, atRest);
        return new CreatedKey(key, token);
    }

    @Override
    public synchronized CreatedKey rotate(String keyId, int overlapSeconds) {
        String atRestOld = idToAtRest.get(keyId);
        ApiKey key = atRestOld == null ? null : byToken.get(atRestOld);
        if (key == null) throw new IllegalArgumentException("unknown key id " + keyId);
        String newToken = "sk-" + HEX.formatHex(randomBytes(24));
        String atRestNew = lookupKey(newToken);
        long overlapEnd = System.currentTimeMillis() + Math.max(0, overlapSeconds) * 1000L;
        if (store instanceof JsonKeyStore jsonStore) {
            jsonStore.rotateKey(atRestOld, newToken, key, overlapEnd);
        } else {
            store.upsert(newToken, key);
            store.rotateAtRest(atRestOld, atRestNew, overlapEnd);
        }
        byToken.remove(atRestOld);
        byToken.put(atRestNew, key);
        idToAtRest.put(keyId, atRestNew);
        legacyTokens.put(atRestOld, key);
        legacyUntil.put(atRestOld, overlapEnd);
        return new CreatedKey(key, newToken);
    }

    @Override
    public synchronized boolean revoke(String keyId) {
        String atRest = idToAtRest.get(keyId);
        if (atRest == null || !byToken.containsKey(atRest)) return false;
        if (store instanceof JsonKeyStore jsonStore) {
            jsonStore.revokeIdentity(keyId);
        } else {
            for (var entry : store.load().entrySet()) {
                if (keyId.equals(entry.getValue().key().id())) store.deleteAtRest(entry.getKey());
            }
            store.deleteAtRest(atRest);
        }
        byToken.entrySet().removeIf(entry -> keyId.equals(entry.getValue().id()));
        legacyTokens.entrySet().removeIf(entry -> {
            if (!keyId.equals(entry.getValue().id())) return false;
            legacyUntil.remove(entry.getKey());
            return true;
        });
        idToAtRest.remove(keyId);
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
            if (System.currentTimeMillis() < until && !legacy.expired(System.currentTimeMillis())) {
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
