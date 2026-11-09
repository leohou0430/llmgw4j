package io.github.leohou0430.llmgw.core.governance;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface ApiKeyRegistry {

    /**
     * @param expiresAtEpochMs 0 = never expires; an expired key is invisible
     *                         to {@link #find(String)} (i.e. 401 for callers).
     * @param group            v13 ownership label (team/project) for aggregated
     *                         spend views; "" = ungrouped.
     * @param budgetMicroUsd   v13 hard $ budget per key (micro-USD, cumulative
     *                         spend). 0 = unmetered.
     */
    record ApiKey(String id, String name, long quotaTokens, Set<String> allowedModels,
                  long expiresAtEpochMs, String group, long budgetMicroUsd,
                  Map<String, String> metadata) {

        public ApiKey(String id, String name, long quotaTokens, Set<String> allowedModels) {
            this(id, name, quotaTokens, allowedModels, 0, "", 0, Map.of());
        }

        public ApiKey(String id, String name, long quotaTokens, Set<String> allowedModels,
                      long expiresAtEpochMs) {
            this(id, name, quotaTokens, allowedModels, expiresAtEpochMs, "", 0, Map.of());
        }

        public ApiKey(String id, String name, long quotaTokens, Set<String> allowedModels,
                      long expiresAtEpochMs, String group, long budgetMicroUsd) {
            this(id, name, quotaTokens, allowedModels, expiresAtEpochMs, group,
                    budgetMicroUsd, Map.of());
        }

        public boolean expired(long nowEpochMs) {
            return expiresAtEpochMs > 0 && nowEpochMs >= expiresAtEpochMs;
        }
    }

    Optional<ApiKey> find(String token);

    java.util.Collection<ApiKey> all();
}
