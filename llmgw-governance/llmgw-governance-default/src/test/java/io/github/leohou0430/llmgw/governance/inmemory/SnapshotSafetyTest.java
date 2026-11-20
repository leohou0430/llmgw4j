package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotSafetyTest {

	@org.junit.jupiter.api.io.TempDir
	java.nio.file.Path folder;

	private JsonSnapshotStore fileStore(InMemoryUsageLedger ledger, java.nio.file.Path path) {
		var properties = new GatewayProperties();
		properties.getGovernance().setSnapshotPath(path.toString());
		return new JsonSnapshotStore(ledger, properties);
	}

	@Test
	void savedSnapshotRoundTripsIntoAnInactiveLedger() {
		var original = new InMemoryUsageLedger();
		original.record(new UsageLedger.UsageRecord("id", "m", 2, 3, 1, true, Instant.now(), 0));
		var path = folder.resolve("snapshot.json");
		fileStore(original, path).save();
		var restored = new InMemoryUsageLedger();
		fileStore(restored, path).load();
		assertEquals(original.snapshot(), restored.snapshot());
	}

	@Test
	void fileFailuresAreReportedAndTemporaryFilesAreCleaned() throws Exception {
		var target = java.nio.file.Files.createDirectory(folder.resolve("blocked"));
		java.nio.file.Files.writeString(target.resolve("keep"), "old data");
		assertThrows(java.io.UncheckedIOException.class, () -> fileStore(new InMemoryUsageLedger(), target).save());
		assertEquals("old data", java.nio.file.Files.readString(target.resolve("keep")));
		try (var files = java.nio.file.Files.list(folder)) {
			assertEquals(java.util.List.of("blocked"), files.map(p -> p.getFileName().toString()).sorted().toList());
		}
		assertThrows(java.io.UncheckedIOException.class,
				() -> fileStore(new InMemoryUsageLedger(), folder.resolve("missing")).load());
	}

	@Test
	void invalidSnapshotShapesAndNumbersNeverChangeUsage() {
		var ledger = new InMemoryUsageLedger();
		ledger.record(new UsageLedger.UsageRecord("id", "m", 2, 3, 1, true, Instant.now(), 0));
		var store = new JsonSnapshotStore(ledger, new GatewayProperties());
		for (String json : java.util.List.of("{}", "{\"usage\":[]}", "{\"usage\":{\"id\":{\"requests\":-1}}}",
				"{\"usage\":{\"id\":{\"requests\":9223372036854775808}}}")) {
			assertThrows(IllegalArgumentException.class, () -> store.importJson(json));
			assertEquals(5, ledger.used("id"));
		}
	}

	@Test
	void apiKeyIdentityCannotShadowModelOrGroupGovernance() {
		for (String id : java.util.List.of("model:shared-model", "group:shared-team")) {
			var p = new GatewayProperties();
			var key = new GatewayProperties.ApiKeyDef();
			key.setId(id);
			key.setToken("token");
			p.setApiKeys(java.util.List.of(key));
			assertThrows(IllegalArgumentException.class, () -> new InMemoryApiKeyRegistry(p));
		}
	}

	@Test
	void malformedJsonIsReportedInsteadOfAcknowledged() {
		assertThrows(IllegalArgumentException.class,
				() -> new JsonSnapshotStore(new InMemoryUsageLedger(), new GatewayProperties()).importJson("{bad"));
	}

	@Test
	void invalidCountersCannotReplaceExistingUsage() {
		var ledger = new InMemoryUsageLedger();
		ledger.record(new UsageLedger.UsageRecord("id", "m", 2, 3, 1, true, Instant.now(), 0));
		var store = new JsonSnapshotStore(ledger, new GatewayProperties());
		assertThrows(IllegalArgumentException.class, () -> store.importJson(
				"{\"usage\":{\"id\":{\"requests\":\"bad\",\"promptTokens\":0,\"completionTokens\":0,\"errors\":0}}}"));
		assertEquals(5, ledger.used("id"));
	}

	@Test
	void storeMustPropagateTheLedgersRestoreFailure() {
		var ledger = (UsageLedger) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { UsageLedger.class }, (proxy, method, args) -> {
					if (method.getName().equals("load"))
						throw new IllegalStateException("active reservation");
					throw new AssertionError(method);
				});
		assertThrows(IllegalStateException.class,
				() -> new JsonSnapshotStore(ledger, new GatewayProperties()).importJson("{\"usage\":{}}"));
	}

	@Test
	void restoreCannotReplaceAnActiveKeysReservations() {
		var ledger = new InMemoryUsageLedger();
		assertTrue(ledger.tryReserve("id", 7, 10));
		assertThrows(IllegalStateException.class,
				() -> ledger.load(Map.of("id", new UsageLedger.UsageSummary(0, 0, 0, 0))));
		assertEquals(7, ledger.used("id"));
	}

	@Test
	void restoreFailureIsAtomicAcrossAllRequestedKeys() {
		var ledger = new InMemoryUsageLedger();
		ledger.record(new UsageLedger.UsageRecord("idle", "m", 2, 3, 1, true, Instant.now(), 0));
		assertTrue(ledger.tryReserve("busy", 1, 10));
		var data = new java.util.LinkedHashMap<String, UsageLedger.UsageSummary>();
		data.put("idle", new UsageLedger.UsageSummary(0, 0, 0, 0));
		data.put("busy", new UsageLedger.UsageSummary(0, 0, 0, 0));
		assertThrows(IllegalStateException.class, () -> ledger.load(data));
		assertEquals(5, ledger.used("idle"));
		assertEquals(1, ledger.used("busy"));
	}

	@Test
	void settlingAndReservingCannotSpendTheSameQuotaTwice() throws Exception {
		try (var workers = Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < 20000; i++) {
				var ledger = new InMemoryUsageLedger();
				assertTrue(ledger.tryReserve("id", 1, 1));
				var go = new CountDownLatch(1);
				var settled = workers.submit(() -> {
					go.await();
					ledger.record(new UsageLedger.UsageRecord("id", "m", 1, 0, 1, true, Instant.now(), 1));
					return true;
				});
				var admitted = workers.submit(() -> {
					go.await();
					return ledger.tryReserve("id", 1, 1);
				});
				go.countDown();
				settled.get(2, TimeUnit.SECONDS);
				assertFalse(admitted.get(2, TimeUnit.SECONDS),
						"settlement transfers held quota to committed usage; it never frees that token");
				assertEquals(1, ledger.used("id"));
			}
		}
	}

}
