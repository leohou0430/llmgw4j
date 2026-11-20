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
 * Cross-replica API key registry. SHA-256(token) -> policy JSON lives in
 * {@code llmgw:keys:sha256}; explicit plaintext mode uses {@code llmgw:keys}. Config keys
 * (and the demo key) seed the selected hash at startup without overwriting existing
 * policy. This registry does not implement KeyAdminService; runtime create/rotate/revoke
 * are unavailable (501) with this pack.
 */
public final class RedisApiKeyRegistry implements ApiKeyRegistry {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final JedisPooled redis;

	private final String hashKey;

	private final boolean production;

	private final boolean hashLookup;

	private final String incompatibleHashKey;

	public RedisApiKeyRegistry(JedisPooled redis, GatewayProperties properties) {
		this(redis, properties, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX);
	}

	public RedisApiKeyRegistry(JedisPooled redis, GatewayProperties properties, String prefix) {
		this.redis = redis;
		this.production = properties.isProduction();
		this.hashLookup = properties.getAuth().isHashKeysAtRest();
		this.hashKey = prefix + (hashLookup ? "keys:sha256" : "keys");
		this.incompatibleHashKey = prefix + (hashLookup ? "keys" : "keys:sha256");
		if (redis.exists(incompatibleHashKey))
			throw storageModeConflict();
		seedFromConfig(properties);
	}

	private static final String SEED = """
			if redis.call('EXISTS', KEYS[2]) == 1 then return -2 end
			for i = 1, #ARGV, 3 do
			  local current = redis.call('HGET', KEYS[1], ARGV[i])
			  local expected = ARGV[i + 1]
			  if current then
			    if current ~= expected and current ~= ARGV[i + 2] then return 0 end
			  elseif expected ~= '' then return 0 end
			end
			for i = 1, #ARGV, 3 do
			  redis.call('HSETNX', KEYS[1], ARGV[i], ARGV[i + 2])
			end
			return 1
			""";

	/**
	 * Validate the whole bootstrap batch before publishing; existing shared policy must
	 * agree.
	 */
	private void seedFromConfig(GatewayProperties properties) {
		Map<String, ApiKey> desired = new java.util.LinkedHashMap<>();
		if (properties.getApiKeys().isEmpty()) {
			if (properties.isProduction()) {
				return;
			}
			desired.put("sk-demo", new ApiKey("demo", "demo",
					io.github.leohou0430.llmgw.governance.GovernanceLimits.from(properties).defaultQuotaTokens(),
					Set.of(), 0, "", 0));
		}
		else {
			for (GatewayProperties.ApiKeyDef def : properties.getApiKeys()) {
				ApiKey key = new ApiKey(def.getId(), def.getName(), def.getQuotaTokens(),
						Set.copyOf(def.getAllowedModels()), 0, def.getGroup(), def.getBudgetMicroUsd(),
						Map.copyOf(def.getMetadata()));
				RedisUsageLedger.validateKeyId(key.id());
				if (key.quotaTokens() > io.github.leohou0430.llmgw.governance.UsageSummaries.MAX_EXACT_COUNTER) {
					throw new IllegalArgumentException("Redis API key quota exceeds exact integer range");
				}
				ApiKey prior = desired.putIfAbsent(def.getToken(), key);
				if (prior != null && !prior.equals(key))
					throw new IllegalStateException("conflicting configured API key policy");
			}
		}
		if (desired.isEmpty())
			return;
		var tokens = new ArrayList<>(desired.keySet());
		var fields = tokens.stream().map(this::lookupKey).toList();
		var existing = redis.hmget(hashKey, fields.toArray(String[]::new));
		var arguments = new ArrayList<String>(tokens.size() * 3);
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i), current = existing.get(i);
			ApiKey key = desired.get(token);
			if (current != null && !key.equals(parse(token, current))) {
				throw new IllegalStateException(
						"shared API key policy conflicts with configuration for id " + key.id());
			}
			arguments.add(fields.get(i));
			arguments.add(current == null ? "" : current);
			arguments.add(encode(key));
		}
		Object seeded = redis.eval(SEED, List.of(hashKey, incompatibleHashKey), arguments);
		if (Long.valueOf(-2).equals(seeded))
			throw storageModeConflict();
		if (!Long.valueOf(1).equals(seeded))
			throw new IllegalStateException(
					"shared API key policy changed during bootstrap; retry with consistent configuration");
	}

	private static IllegalStateException storageModeConflict() {
		return new IllegalStateException(
				"conflicting Redis credential storage mode; coordinate an offline migration before changing hash-keys-at-rest");
	}

	private String lookupKey(String token) {
		if (!hashLookup)
			return token;
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String encode(ApiKey key) {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("id", key.id());
		node.put("name", key.name());
		node.put("quotaTokens", key.quotaTokens());
		node.put("expiresAtEpochMs", key.expiresAtEpochMs());
		node.put("group", key.group());
		node.put("budgetMicroUsd", key.budgetMicroUsd());
		var models = node.putArray("allowedModels");
		key.allowedModels().stream().sorted().forEach(models::add);
		ObjectNode metadata = node.putObject("metadata");
		key.metadata()
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
		return MAPPER.writeValueAsString(node);
	}

	private final io.github.leohou0430.llmgw.governance.redis.RedisFailurePolicy failurePolicy = io.github.leohou0430.llmgw.governance.redis.RedisFailurePolicy
		.failClosed();

	@Override
	public Optional<ApiKey> find(String token) {
		if (token == null || token.isBlank() || (production && "sk-demo".equals(token))) {
			return Optional.empty();
		}
		// Defined branch (Chapter-9 §9.2.7): authentication CANNOT fail open —
		// an unresolvable credential store is 503, never anonymous admission.
		Object json = failurePolicy.required("api-key lookup", () -> redis.hget(hashKey, lookupKey(token)));
		if (json == null) {
			return Optional.empty();
		}
		return Optional.of(parse(token, json.toString())).filter(key -> !key.expired(System.currentTimeMillis()));
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
		Map<String, String> metadata = new java.util.HashMap<>();
		node.path("metadata").properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
		String id = node.path("id").asText("");
		RedisUsageLedger.validateKeyId(id);
		return new ApiKey(id, node.path("name").asText(""), node.path("quotaTokens").asLong(0), models,
				node.path("expiresAtEpochMs").asLong(0), node.path("group").asText(""),
				node.path("budgetMicroUsd").asLong(0), Map.copyOf(metadata));
	}

}
