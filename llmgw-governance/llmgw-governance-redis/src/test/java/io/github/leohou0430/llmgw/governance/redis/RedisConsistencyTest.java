package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.JedisPooled;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LLMGW_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisConsistencyTest {

	static JedisPooled redis;

	final String prefix = "llmgw-stage2:" + UUID.randomUUID() + ":";

	@BeforeAll
	static void connect() {
		redis = new JedisPooled("127.0.0.1", Integer.parseInt(System.getenv("LLMGW_TEST_REDIS_PORT")));
	}

	@AfterAll
	static void close() {
		redis.close();
	}

	@AfterEach
	void cleanup() {
		var keys = redis.keys(prefix + "*");
		if (!keys.isEmpty())
			redis.del(keys.toArray(String[]::new));
	}

	@Test
	void differentReplicaClocksCannotRefillTheSameBucketImmediately() {
		var scripts = new RedisScripts();
		var key = List.of(prefix + "bucket:key");
		long now = System.currentTimeMillis();
		assertEquals(1L, scripts.execute(redis, scripts.acquire, key, "1", "0.001", Long.toString(now), "1"));
		assertEquals(0L, scripts.execute(redis, scripts.acquire, key, "1", "0.001", Long.toString(now + 3600000), "1"),
				"a fast replica must not mint an hour of credit");
	}

	@Test
	void aSlowReplicaCannotMoveTheStoredClockBackwards() {
		var scripts = new RedisScripts();
		var key = List.of(prefix + "bucket:key");
		long future = System.currentTimeMillis() + 3600000;
		redis.hset(key.getFirst(), Map.of("tokens", "0", "ts", Long.toString(future)));
		assertEquals(0L,
				scripts.execute(redis, scripts.acquire, key, "1", "0.001", Long.toString(future - 3600000), "1"));
		assertEquals(future, Long.parseLong(redis.hget(key.getFirst(), "ts")),
				"clock rollback must not prepare a second refill");
	}

	@Test
	void staleBootstrapCannotOverwriteSharedCredentialPolicy() {
		new RedisApiKeyRegistry(redis, properties("token", 1), prefix);
		assertThrows(IllegalStateException.class,
				() -> new RedisApiKeyRegistry(redis, properties("token", 100), prefix));
		assertEquals(1,
				new RedisApiKeyRegistry(redis, properties("token", 1), prefix).find("token")
					.orElseThrow()
					.quotaTokens());
	}

	@Test
	void failedBootstrapDoesNotPublishSomeOfItsNewCredentials() {
		new RedisApiKeyRegistry(redis, properties("existing", 1), prefix);
		var p = properties("new-token", 100);
		var conflict = properties("existing", 2).getApiKeys().getFirst();
		p.setApiKeys(List.of(p.getApiKeys().getFirst(), conflict));
		assertThrows(IllegalStateException.class, () -> new RedisApiKeyRegistry(redis, p, prefix));
		assertNull(redis.hget(prefix + "keys", "new-token"));
	}

	@Test
	void restoreMustNotEraseAnotherReplicasActiveReservation() {
		var a = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		var b = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		assertTrue(a.tryReserve("tenant", 7, 10));
		assertThrows(IllegalStateException.class, () -> b.load(
				Map.of("tenant", new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageSummary(0, 0, 0, 0))));
		assertEquals(7, a.used("tenant"));
		assertFalse(b.tryReserve("tenant", 4, 10));
	}

	@Test
	void anInternalIndexNameCannotCorruptTheSharedLedger() {
		var a = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		// Rejecting an internal index ID must not poison unrelated IDs.
		try {
			a.tryReserve("keys", 1, 10);
		}
		catch (RuntimeException unavailable) {
			/* fail closed is acceptable */ }
		assertDoesNotThrow(() -> assertTrue(a.tryReserve("unrelated", 1, 10)));
	}

	@Test
	void aBrokenIndexCannotLeaveAReservationAfterAdmissionFails() {
		var ledger = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		redis.set(prefix + "usage:keys", "broken");
		assertThrows(RuntimeException.class, () -> ledger.tryReserve("tenant", 1, 10));
		assertFalse(redis.exists(prefix + "usage:tenant"), "a script error must not leave a partial debit");
	}

	@Test
	void expiredSharedCredentialsDoNotAuthenticate() {
		new RedisApiKeyRegistry(redis, properties("token", 1), prefix);
		String value = redis.hget(prefix + "keys", "token").replace("\"expiresAtEpochMs\":0", "\"expiresAtEpochMs\":1");
		redis.hset(prefix + "keys", "token", value);
		var readOnly = new GatewayProperties();
		readOnly.setProduction(true);
		readOnly.getAuth().setHashKeysAtRest(false);
		assertTrue(new RedisApiKeyRegistry(redis, readOnly, prefix).find("token").isEmpty());
	}

	@Test
	void bootstrapPreservesConfiguredMetadata() {
		var p = properties("token", 1);
		p.getApiKeys().getFirst().setMetadata(Map.of("owner", "team-a"));
		assertEquals(Map.of("owner", "team-a"),
				new RedisApiKeyRegistry(redis, p, prefix).find("token").orElseThrow().metadata());
	}

	@Test
	void quotasOutsideRedisExactIntegerRangeAreRejected() {
		var ledger = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		long imprecise = 9007199254740992L;
		redis.hset(prefix + "usage:tenant", Map.of("used", Long.toString(imprecise), "reserved", "0"));
		assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve("tenant", 1, imprecise));
		assertEquals("0", redis.hget(prefix + "usage:tenant", "reserved"));
	}

	@Test
	void hashAtRestActuallyAvoidsPlaintextCredentialFields() throws Exception {
		var p = properties("sensitive-token", 1);
		p.getAuth().setHashKeysAtRest(true);
		var registry = new RedisApiKeyRegistry(redis, p, prefix);
		String digest = java.util.HexFormat.of()
			.formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest("sensitive-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertNull(redis.hget(prefix + "keys", "sensitive-token"));
		assertNotNull(redis.hget(prefix + "keys:sha256", digest));
		assertTrue(registry.find("sensitive-token").isPresent());
	}

	@Test
	void existingPlaintextRequiresAnExplicitMigrationBeforeHashedMode() {
		var plain = properties("token", 1);
		plain.getAuth().setHashKeysAtRest(false);
		new RedisApiKeyRegistry(redis, plain, prefix);
		var hashed = properties("token", 1);
		hashed.getAuth().setHashKeysAtRest(true);
		assertThrows(IllegalStateException.class, () -> new RedisApiKeyRegistry(redis, hashed, prefix));
		assertNotNull(redis.hget(prefix + "keys", "token"));
		assertFalse(redis.exists(prefix + "keys:sha256"));
	}

	@Test
	void plaintextModeCannotSilentlySplitAnExistingHashedRegistry() {
		var hashed = properties("token", 1);
		hashed.getAuth().setHashKeysAtRest(true);
		new RedisApiKeyRegistry(redis, hashed, prefix);
		var plain = properties("token", 1);
		plain.getAuth().setHashKeysAtRest(false);
		assertThrows(IllegalStateException.class, () -> new RedisApiKeyRegistry(redis, plain, prefix));
	}

	@Test
	void malformedCountersCannotReleaseHeldQuotaOnSettlementFailure() {
		var ledger = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		assertTrue(ledger.tryReserve("tenant", 7, 10));
		redis.hset(prefix + "usage:tenant", "requests", "broken");
		assertThrows(RuntimeException.class,
				() -> ledger.record(new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageRecord("tenant",
						"m", 1, 0, 1, true, java.time.Instant.now(), 7)));
		assertEquals("7", redis.hget(prefix + "usage:tenant", "reserved"));
	}

	@Test
	void identicalConcurrentBootstrapPublishesOneSharedPolicy() throws Exception {
		try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var futures = new ArrayList<java.util.concurrent.Future<?>>();
			for (int i = 0; i < 16; i++)
				futures.add(workers.submit(() -> {
					start.await();
					assertEquals(10,
							new RedisApiKeyRegistry(redis, properties("shared", 10), prefix).find("shared")
								.orElseThrow()
								.quotaTokens());
					return null;
				}));
			start.countDown();
			for (var future : futures)
				future.get();
		}
		assertEquals(1, redis.hlen(prefix + "keys"));
	}

	@Test
	void snapshotRestorePreservesUnrelatedIdentities() {
		var ledger = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		var summary = new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageSummary(1, 2, 3, 0);
		ledger.load(Map.of("existing", summary));
		ledger.load(Map.of("new", summary));
		assertEquals(Map.of("existing", summary, "new", summary), ledger.snapshot());
	}

	@Test
	void oneBusyIdentityRejectsTheWholeRestoreBatch() {
		var ledger = new RedisUsageLedger(redis, new RedisScripts(), prefix);
		var old = new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageSummary(1, 2, 3, 0);
		var empty = new io.github.leohou0430.llmgw.core.governance.UsageLedger.UsageSummary(0, 0, 0, 0);
		ledger.load(Map.of("idle", old));
		assertTrue(ledger.tryReserve("busy", 7, 10));
		assertThrows(IllegalStateException.class, () -> ledger.load(Map.of("idle", empty, "busy", empty)));
		assertEquals(old, ledger.snapshot().get("idle"));
		assertEquals(7, ledger.used("busy"));
	}

	@Test
	void openCircuitRejectsRequestsForTheWholeCooldown() {
		var scripts = new RedisScripts();
		scripts.execute(redis, scripts.breakerFailure,
				List.of(prefix + "fail", prefix + "open", prefix + "tripped", prefix + "probe"), "1", "10000");
		assertEquals(0L, scripts.execute(redis, scripts.breakerAllow,
				List.of(prefix + "open", prefix + "probe", prefix + "tripped")));
	}

	@Test
	void cooldownExpiryAdmitsOnlyOneProbeAcrossReplicas() throws Exception {
		var scripts = new RedisScripts();
		scripts.execute(redis, scripts.breakerFailure,
				List.of(prefix + "fail", prefix + "open", prefix + "tripped", prefix + "probe"), "1", "10000");
		redis.del(prefix + "open"); // Deterministically model expiry of the cooldown
									// marker.
		try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var results = new ArrayList<java.util.concurrent.Future<Long>>();
			for (int i = 0; i < 16; i++)
				results.add(workers.submit(() -> {
					start.await();
					return scripts.execute(redis, scripts.breakerAllow,
							List.of(prefix + "open", prefix + "probe", prefix + "tripped"));
				}));
			start.countDown();
			long allowed = 0;
			for (var result : results)
				allowed += result.get();
			assertEquals(1, allowed, "cooldown expiry must not unleash all replicas at once");
		}
	}

	@Test
	void sharedProbeFailureReopensAndSuccessClosesTheCircuit() {
		var scripts = new RedisScripts();
		var a = new RedisCircuitBreaker(redis, scripts, prefix, 2, 10000);
		var b = new RedisCircuitBreaker(redis, scripts, prefix, 2, 10000);
		var route = new io.github.leohou0430.llmgw.core.routing.Route("r", "m", "m", "http://localhost", "", 1,
				List.of());
		a.recordFailure(route);
		b.recordFailure(route);
		assertFalse(a.allow(route));
		redis.del(prefix + "cb:open:r");
		assertTrue(a.allow(route));
		assertFalse(b.allow(route));
		a.recordFailure(route);
		assertFalse(b.allow(route), "failed half-open probe must restart cooldown immediately");
		redis.del(prefix + "cb:open:r");
		assertTrue(b.allow(route));
		redis.del(prefix + "cb:probe:r");
		assertTrue(a.allow(route), "a lost probe lease must be recoverable");
		a.recordSuccess(route);
		assertTrue(a.allow(route));
		assertTrue(b.allow(route));
		assertFalse(redis.exists(prefix + "cb:tripped:r"));
		assertFalse(redis.exists(prefix + "cb:probe:r"));
	}

	private GatewayProperties properties(String token, long quota) {
		var p = new GatewayProperties();
		p.getAuth().setHashKeysAtRest(false);
		var key = new GatewayProperties.ApiKeyDef();
		key.setId("tenant");
		key.setToken(token);
		key.setQuotaTokens(quota);
		p.setApiKeys(List.of(key));
		return p;
	}

}
