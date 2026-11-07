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

    private final Map<String, ApiKey> byToken = new ConcurrentHashMap<>();

    public InMemoryApiKeyRegistry(GatewayProperties properties) {
        if (properties.getApiKeys().isEmpty()) {
            byToken.put("sk-demo", new ApiKey("demo", "demo",
                    GovernanceLimits.from(properties).defaultQuotaTokens(), Set.of()));
        } else {
            for (GatewayProperties.ApiKeyDef def : properties.getApiKeys()) {
                byToken.put(def.getToken(), new ApiKey(def.getId(), def.getName(),
                        def.getQuotaTokens(), Set.copyOf(def.getAllowedModels())));
            }
        }
    }

    @Override
    public Optional<ApiKey> find(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    @Override
    public Collection<ApiKey> all() {
        return List.copyOf(byToken.values());
    }
}
