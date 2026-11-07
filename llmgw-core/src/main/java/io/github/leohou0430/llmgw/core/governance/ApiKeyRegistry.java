package io.github.leohou0430.llmgw.core.governance;

import java.util.Optional;
import java.util.Set;

public interface ApiKeyRegistry {

    record ApiKey(String id, String name, long quotaTokens, Set<String> allowedModels) {
    }

    Optional<ApiKey> find(String token);

    java.util.Collection<ApiKey> all();
}
