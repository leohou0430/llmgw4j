package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * W3.6: cross-replica API key registry. Keys live in ONE Redis hash
 * ({@code llmgw:keys}, token -> JSON), so a key created on replica A
 * authenticates on replica B immediately. Config keys (and the demo key) seed
 * the hash at startup. This registry does not implement KeyAdminService;
 * runtime create/rotate/revoke are unavailable (501) with this pack.
 */
public final class RedisApiKeyRegistry implements ApiKeyRegistry {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JedisPooled redis;
    private final String hashKey;
    private final boolean production;

    public RedisApiKeyRegistry(JedisPooled redis, GatewayProperties properties) {
        this(redis, properties, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX);
    }

    public RedisApiKeyRegistry(JedisPooled redis, GatewayProperties properties,
                               String prefix) {
        this.redis = redis;
        this.production = properties.isProduction();
        this.hashKey = prefix + "keys";
        seedFromConfig(properties);
    }

    /** Config keys are the static source: (re)write them into the hash. */
    private void seedFromConfig(GatewayProperties properties) {
        if (properties.getApiKeys().isEmpty()) {
            if (properties.isProduction()) {
                return;
            }
            upsert("sk-demo", new ApiKey("demo", "demo",
                    io.github.leohou0430.llmgw.governance.GovernanceLimits
                            .from(properties).defaultQuotaTokens(),
                    Set.of(), 0, "", 0));
        } else {
            for (GatewayProperties.ApiKeyDef def : properties.getApiKeys()) {
                upsert(def.getToken(), new ApiKey(def.getId(), def.getName(),
                        def.getQuotaTokens(), Set.copyOf(def.getAllowedModels()),
                        0, def.getGroup(), def.getBudgetMicroUsd()));
            }
        }
    }

    private void upsert(String token, ApiKey key) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", key.id());
        node.put("name", key.name());
        node.put("quotaTokens", key.quotaTokens());
        node.put("expiresAtEpochMs", key.expiresAtEpochMs());
        node.put("group", key.group());
        node.put("budgetMicroUsd", key.budgetMicroUsd());
        var models = node.putArray("allowedModels");
        key.allowedModels().forEach(models::add);
        redis.hset(hashKey, token, MAPPER.writeValueAsString(node));
    }

    @Override
    public Optional<ApiKey> find(String token) {
        if (token == null || token.isBlank() || (production && "sk-demo".equals(token))) {
            return Optional.empty();
        }
        Object json = redis.hget(hashKey, token);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(parse(token, json.toString()));
    }

    @Override
    public Collection<ApiKey> all() {
        Map<String, String> entries = redis.hgetAll(hashKey);
        List<ApiKey> out = new ArrayList<>(entries.size());
        entries.forEach((token, json) -> out.add(parse(token, json)));
        return List.copyOf(out);
    }

    private static ApiKey parse(String token, String json) {
        var node = MAPPER.readTree(json);
        Set<String> models = new java.util.HashSet<>();
        node.withArray("allowedModels").forEach(n -> models.add(n.asText()));
        return new ApiKey(node.path("id").asText(""),
                node.path("name").asText(""),
                node.path("quotaTokens").asLong(0),
                models,
                node.path("expiresAtEpochMs").asLong(0),
                node.path("group").asText(""),
                node.path("budgetMicroUsd").asLong(0));
    }
}
