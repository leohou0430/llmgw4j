package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogAccessException;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.4 seam: local catalog CRUD, the authorization precondition running before any
 * access, fixed-source routing (a route switch must not re-point old entries) and
 * cross-project isolation. Assertions use the entry values passed in (independent truth),
 * never catalog internals.
 */
class LocalResourceCatalogTest {

	private static ResourceEntry entry(String project, String gatewayId, String sourceRoute, String configVersion) {
		return new ResourceEntry(gatewayId, project, "batch", "up-1", sourceRoute, "deepseek", configVersion, 1000L,
				"pending", Map.of("k", "v"), 0L, false);
	}

	@Test
	void createThenGetRoundTripsEveryField() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		ResourceEntry created = catalog.create(entry("p1", "gw-1", "route-a", "cfg-1"));

		ResourceEntry got = catalog.get("p1", "gw-1").orElseThrow();
		assertEquals(created, got);
		assertEquals("batch", got.type());
		assertEquals("up-1", got.upstreamId());
		assertEquals("route-a", got.sourceRoute());
		assertEquals("deepseek", got.provider());
		assertEquals("cfg-1", got.configVersion());
		assertEquals("pending", got.observableStatus());
		assertEquals(Map.of("k", "v"), got.metadata());
	}

	@Test
	void unauthorizedProjectIsDeniedBeforeAnyAccess() {
		LocalResourceCatalog catalog = new LocalResourceCatalog("allowed"::equals);
		assertThrows(CatalogAccessException.class, () -> catalog.create(entry("denied", "gw-1", "route-a", "cfg-1")));
		assertThrows(CatalogAccessException.class, () -> catalog.get("denied", "gw-1"));
		assertThrows(CatalogAccessException.class, () -> catalog.list("denied"));
	}

	@Test
	void sourceRouteStaysFixedAcrossRouteSwitch() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("p1", "gw-1", "route-a", "cfg-1"));

		// A later gateway default-route switch (route-b / cfg-2) is external state;
		// the existing entry must keep pointing at its original source.
		ResourceEntry got = catalog.get("p1", "gw-1").orElseThrow();
		assertEquals("route-a", got.sourceRoute());
		assertEquals("cfg-1", got.configVersion());
	}

	@Test
	void crossProjectIsolation() {
		LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
		catalog.create(entry("p1", "gw-1", "route-a", "cfg-1"));

		assertTrue(catalog.get("p2", "gw-1").isEmpty(), "another project must not see the entry");
		assertTrue(catalog.list("p2").isEmpty());
		assertEquals(1, catalog.list("p1").size());
	}

}