package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteKeyStore;
import java.security.SecureRandom;
import java.util.*;
import java.sql.SQLException;

/**
 * One durable authority; reads see committed control-plane mutations without a cache
 * refresh race.
 */
public final class SqliteApiKeyRegistry implements ApiKeyRegistry, KeyAdminService {

	private final SqliteKeyStore store;

	private final DatabaseContext database;

	private final SecureRandom random = new SecureRandom();

	private final boolean production;

	public SqliteApiKeyRegistry(GatewayProperties properties, DatabaseContext database, SqliteKeyStore store,
			KeyStore legacy) {
		this.store = store;
		this.database = database;
		production = properties.isProduction();
		try {
			database.transaction(c -> {
				try (var s = c.prepareStatement("SELECT 1 FROM runtime_bootstrap WHERE name='credentials'");
						var rs = s.executeQuery()) {
					if (rs.next())
						return null;
				}
				if (legacy != null)
					legacy.load().forEach((atRest, value) -> {
						String imported = legacyDigest(atRest, properties.getAuth().isHashKeysAtRest());
						String rotatedTo = value.rotatedTo() == null ? null
								: legacyDigest(value.rotatedTo(), properties.getAuth().isHashKeysAtRest());
						if (store.findAtRest(imported).isEmpty())
							store.importAtRest(imported,
									new KeyStore.StoredKey(value.key(), rotatedTo, value.legacyUntil()));
					});
				var definitions = properties.getApiKeys();
				if (definitions.isEmpty() && !production && store.findToken("sk-demo").isEmpty())
					store.upsert("sk-demo",
							new ApiKey("demo", "demo", properties.getGovernance().getDefaultQuotaTokens(), Set.of()));
				for (var def : definitions) {
					if (store.findToken(def.getToken()).isPresent())
						continue;
					if (byId(def.getId()).isPresent())
						throw new IllegalStateException(
								"credential identity conflicts with existing store: " + def.getId());
					store.upsert(def.getToken(),
							new ApiKey(def.getId(), def.getName(), def.getQuotaTokens(),
									Set.copyOf(def.getAllowedModels()), 0, def.getGroup(), def.getBudgetMicroUsd(),
									Map.copyOf(def.getMetadata())));
				}
				try (var s = c.prepareStatement("INSERT INTO runtime_bootstrap(name) VALUES('credentials')")) {
					s.executeUpdate();
				}
				return null;
			});
		}
		catch (SQLException e) {
			throw new IllegalStateException("credential bootstrap failed", e);
		}
	}

	@Override
	public Optional<ApiKey> find(String token) {
		if (token == null || token.isBlank() || (production && token.equals("sk-demo")))
			return Optional.empty();
		return store.findToken(token)
			.filter(s -> !s.key().expired(System.currentTimeMillis()))
			.filter(s -> s.rotatedTo() == null
					|| (s.legacyUntil() > System.currentTimeMillis() && store.findAtRest(s.rotatedTo())
						.filter(next -> next.key().id().equals(s.key().id()))
						.isPresent()))
			.map(KeyStore.StoredKey::key);
	}

	@Override
	public Collection<ApiKey> all() {
		return store.load().values().stream().filter(s -> s.rotatedTo() == null).map(KeyStore.StoredKey::key).toList();
	}

	@Override
	public Optional<ApiKey> byId(String id) {
		return all().stream().filter(k -> k.id().equals(id)).findFirst();
	}

	private String token() {
		byte[] b = new byte[32];
		random.nextBytes(b);
		return "sk-" + HexFormat.of().formatHex(b);
	}

	@Override
	public CreatedKey create(String name, long quota, Set<String> models, Long ttl) {
		return create(name, "", 0, quota, models, ttl, Map.of());
	}

	@Override
	public CreatedKey create(String name, String group, long budget, long quota, Set<String> models, Long ttl) {
		return create(name, group, budget, quota, models, ttl, Map.of());
	}

	@Override
	public CreatedKey create(String name, String group, long budget, long quota, Set<String> models, Long ttl,
			Map<String, String> metadata) {
		String token = token(), id = UUID.randomUUID().toString();
		// key_store.group_name is NOT NULL and "" means ungrouped, so a missing
		// group on the management request must normalize instead of tripping
		// the constraint into an uncaught 500.
		String normalizedGroup = group == null || group.isBlank() ? "" : group;
		var key = new ApiKey(id, name == null ? id : name, quota, Set.copyOf(models),
				ttl == null ? 0 : Math.addExact(System.currentTimeMillis(), Math.multiplyExact(Math.max(0, ttl), 1000)),
				normalizedGroup, budget, Map.copyOf(metadata));
		store.upsert(token, key);
		return new CreatedKey(key, token);
	}

	@Override
	public CreatedKey rotate(String id, int overlapSeconds) {
		try {
			return database.transaction(c -> {
				var old = store.load()
					.entrySet()
					.stream()
					.filter(e -> e.getValue().rotatedTo() == null && e.getValue().key().id().equals(id))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("unknown key"));
				String token = token();
				store.upsert(token, old.getValue().key());
				String hash = digest(token);
				store.rotateAtRest(old.getKey(), hash,
						System.currentTimeMillis() + Math.max(0, overlapSeconds) * 1000L);
				return new CreatedKey(old.getValue().key(), token);
			});
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String legacyDigest(String value, boolean hashedLegacy) {
		return hashedLegacy && value.matches("[0-9a-f]{64}") ? value : digest(value);
	}

	private static String digest(String token) {
		try {
			return HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public boolean revoke(String id) {
		try {
			return database.transaction(c -> {
				boolean found = false;
				for (var e : store.load().entrySet())
					if (e.getValue().key().id().equals(id)) {
						store.deleteAtRest(e.getKey());
						found = true;
					}
				return found;
			});
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

}
