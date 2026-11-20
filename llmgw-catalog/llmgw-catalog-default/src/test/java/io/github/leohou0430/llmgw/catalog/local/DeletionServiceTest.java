package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.DeletionRecord;
import io.github.leohou0430.llmgw.catalog.DeletionState;
import io.github.leohou0430.llmgw.catalog.RawContentUnavailableException;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.8 seam: the deletion state machine moves PENDING → CONFIRMED only when the upstream
 * confirms; a failure leaves it UNKNOWN (never auto-retried) and a manual reconciliation
 * can resolve it. Raw content is rejected, and removing a catalog entry is not the same
 * as an upstream delete.
 */
class DeletionServiceTest {

	private static ResourceEntry entry(String gatewayId) {
		return new ResourceEntry(gatewayId, "p1", "batch", "up-1", "route-a", "deepseek", "cfg-1", 1000L, "pending",
				Map.of(), 0L, false);
	}

	private static DeletionService service(LocalResourceCatalog catalog) {
		return new DeletionService(catalog, () -> 999L);
	}

	@Test
	void confirmedWhenUpstreamConfirms() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);

		AtomicInteger calls = new AtomicInteger();
		DeletionRecord record = service.requestDelete("p1", "gw-1", (source, upstreamId) -> calls.incrementAndGet());

		assertEquals(DeletionState.CONFIRMED, record.state());
		assertEquals(1, calls.get());
		assertEquals(DeletionState.CONFIRMED, service.state("p1", "gw-1"));
		assertEquals(999L, record.observedAt());
	}

	@Test
	void unknownWhenUpstreamFailsWithNoAutoRetry() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);

		AtomicInteger calls = new AtomicInteger();
		DeletionRecord record = service.requestDelete("p1", "gw-1", (source, upstreamId) -> {
			calls.incrementAndGet();
			throw new RuntimeException("upstream timeout");
		});

		assertEquals(DeletionState.UNKNOWN, record.state());
		assertEquals(1, calls.get(), "non-idempotent delete must not auto-retry");
		assertEquals(DeletionState.UNKNOWN, service.state("p1", "gw-1"));
	}

	@Test
	void manualReconcileResolvesUnknown() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);
		service.requestDelete("p1", "gw-1", (source, upstreamId) -> {
			throw new RuntimeException("timeout");
		});

		DeletionRecord reconciled = service.reconcile("p1", "gw-1", (source, upstreamId) -> true);

		assertEquals(DeletionState.CONFIRMED, reconciled.state());
		assertEquals(2, reconciled.attempts());
	}

	@Test
	void reconcileKeepsUnknownWhenStillNotDeleted() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);
		service.requestDelete("p1", "gw-1", (source, upstreamId) -> {
			throw new RuntimeException("timeout");
		});

		assertEquals(DeletionState.UNKNOWN, service.reconcile("p1", "gw-1", (source, upstreamId) -> false).state());
	}

	@Test
	void rawContentRequestIsRejected() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);

		assertThrows(RawContentUnavailableException.class, () -> service.downloadRaw("p1", "gw-1"));
	}

	@Test
	void removingCatalogEntryIsNotUpstreamDeletion() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("gw-1"));
		DeletionService service = service(catalog);

		catalog.delete("p1", "gw-1");

		assertTrue(catalog.get("p1", "gw-1").isEmpty(), "catalog entry removed");
		assertNull(service.state("p1", "gw-1"),
				"removing the local entry must not be recorded as an upstream deletion");
	}

}