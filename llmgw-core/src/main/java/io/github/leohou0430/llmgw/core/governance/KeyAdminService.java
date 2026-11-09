package io.github.leohou0430.llmgw.core.governance;

import java.util.Optional;
import java.util.Set;

/**
 * Runtime key lifecycle (v13): create / revoke / lookup-by-id. Deliberately a
 * SEPARATE interface from {@link ApiKeyRegistry} -- the hot path only ever
 * reads, and a read-only registry (e.g. a config-fed one) must stay a valid
 * implementation. Admin endpoints require a {@code KeyAdminService} bean.
 *
 * <p>The plaintext token is returned EXACTLY ONCE by {@link #create} and never
 * again: {@link #byId} and {@link ApiKeyRegistry#all} expose metadata only.
 */
public interface KeyAdminService {

    record CreatedKey(ApiKeyRegistry.ApiKey key, String token) {
    }

    /**
     * @param ttlSeconds null = never expires; otherwise the key dies
     *                   ttlSeconds after creation.
     */
    CreatedKey create(String name, long quotaTokens, Set<String> allowedModels, Long ttlSeconds);

    /** Full form: ownership group and hard $ budget (micro-USD, 0 = unmetered). */
    CreatedKey create(String name, String group, long budgetMicroUsd, long quotaTokens,
                      Set<String> allowedModels, Long ttlSeconds);

    /** B7: full form plus free-form metadata. */
    CreatedKey create(String name, String group, long budgetMicroUsd, long quotaTokens,
                      Set<String> allowedModels, Long ttlSeconds,
                      java.util.Map<String, String> metadata);

    /**
     * B1 key rotation: mint a NEW token bound to the same key; the old token
     * stays valid for {@code overlapSeconds} so clients can migrate without
     * downtime. Throws IllegalArgumentException for unknown key ids.
     */
    CreatedKey rotate(String keyId, int overlapSeconds);

    /** true when a key with this id existed and is now revoked. */
    boolean revoke(String keyId);

    Optional<ApiKeyRegistry.ApiKey> byId(String keyId);
}
