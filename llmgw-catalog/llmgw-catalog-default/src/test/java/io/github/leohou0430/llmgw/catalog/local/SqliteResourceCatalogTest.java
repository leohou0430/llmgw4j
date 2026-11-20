package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogCursorException;
import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteResourceCatalogTest {

	private Connection connection;

	private SqliteResourceCatalog catalog;

	@BeforeEach
	void setUp() throws Exception {
		connection = DriverManager.getConnection("jdbc:sqlite::memory:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE resource_catalog (id TEXT PRIMARY KEY, project_id TEXT NOT NULL,"
					+ " type TEXT NOT NULL, upstream_id TEXT NOT NULL, source_route TEXT NOT NULL,"
					+ " provider TEXT NOT NULL, config_version TEXT NOT NULL, created_at INTEGER NOT NULL,"
					+ " observable_status TEXT NOT NULL, metadata TEXT, last_synced_at INTEGER,"
					+ " deleted INTEGER NOT NULL DEFAULT 0, creation_seq INTEGER NOT NULL DEFAULT 0)");
		}
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE catalog_sequence(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
			ddl.execute("INSERT INTO catalog_sequence VALUES(1,0)");
		}
		DatabaseContext database = new DatabaseContext() {
			@Override
			public Connection connection() {
				return connection;
			}

			@Override
			public void close() {
			}
		};
		catalog = new SqliteResourceCatalog(database, projectId -> !"forbidden".equals(projectId));
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	private static ResourceEntry entry(String gatewayId, String projectId, String type) {
		return new ResourceEntry(gatewayId, projectId, type, "up-" + gatewayId, "route-a", "deepseek", "v1",
				1_700_000_000_000L, "ready", Map.of("k", "v=1&2"), 1_700_000_000_001L, false);
	}

	@Test
	void oldCursorExcludesNewRowsAfterDeletingHighestSequence() {
		catalog.create(entry("one", "p1", "file"));
		catalog.create(entry("two", "p1", "file"));
		catalog.create(entry("three", "p1", "file"));
		var first = catalog.page("t1", "p1", PageRequest.of(1), null);
		catalog.delete("p1", "three");
		catalog.create(entry("new", "p1", "file"));
		var rest = catalog.page("t1", "p1", PageRequest.of(50), first.nextCursor());
		assertEquals(List.of("two"), rest.items().stream().map(ResourceEntry::gatewayId).toList());
	}

	@Test
	void sameGatewayIdInTwoProjectsIsIsolated() {
		catalog.create(entry("f1", "p1", "file"));
		catalog.create(entry("f1", "p2", "file"));

		assertTrue(catalog.get("p1", "f1").isPresent());
		assertEquals(1, catalog.list("p1").size());
		assertEquals(1, catalog.list("p2").size());
		assertTrue(catalog.delete("p1", "f1"));
		assertTrue(catalog.list("p1").isEmpty());
		assertTrue(catalog.get("p2", "f1").isPresent(), "deleting p1 must not touch p2");
	}

	@Test
	void roundTripsEntryFieldsIncludingMetadata() {
		catalog.create(entry("f1", "p1", "file"));
		ResourceEntry loaded = catalog.get("p1", "f1").orElseThrow();

		assertEquals("up-f1", loaded.upstreamId());
		assertEquals("route-a", loaded.sourceRoute());
		assertEquals("deepseek", loaded.provider());
		assertEquals("v1", loaded.configVersion());
		assertEquals("ready", loaded.observableStatus());
		assertEquals(Map.of("k", "v=1&2"), loaded.metadata());
		assertEquals(1_700_000_000_001L, loaded.lastSyncedAt());
		assertFalse(loaded.deleted());
	}

	@Test
	void recreateMovesEntryToTheEndOfTheOrder() {
		catalog.create(entry("a", "p1", "file"));
		catalog.create(entry("b", "p1", "file"));
		catalog.create(entry("a", "p1", "file"));

		assertEquals(List.of("b", "a"), catalog.list("p1").stream().map(ResourceEntry::gatewayId).toList());
	}

	@Test
	void pagesAreStableAndHighWaterMarked() {
		for (int i = 1; i <= 5; i++) {
			catalog.create(entry("g" + i, "p1", "file"));
		}
		CatalogPage first = catalog.page("t1", "p1", PageRequest.of(2), null);
		assertEquals(List.of("g1", "g2"), first.items().stream().map(ResourceEntry::gatewayId).toList());
		assertTrue(first.hasMore());

		catalog.create(entry("g6", "p1", "file"));

		CatalogPage second = catalog.page("t1", "p1", PageRequest.of(2), first.nextCursor());
		assertEquals(List.of("g3", "g4"), second.items().stream().map(ResourceEntry::gatewayId).toList());
		assertTrue(second.items().stream().noneMatch(e -> e.gatewayId().equals("g6")),
				"entries created after the first page must not leak into the traversal");
	}

	@Test
	void cursorBindsTenantProjectAndFilter() {
		catalog.create(entry("g1", "p1", "file"));
		catalog.create(entry("g2", "p1", "file"));
		CatalogPage page = catalog.page("t1", "p1", PageRequest.of(1), null);
		assertThrows(CatalogCursorException.class,
				() -> catalog.page("t2", "p1", PageRequest.of(1), page.nextCursor()));
		assertThrows(CatalogCursorException.class,
				() -> catalog.page("t1", "p1", PageRequest.of(1, "batch"), page.nextCursor()));
		assertThrows(CatalogCursorException.class, () -> catalog.page("t1", "p1", PageRequest.of(1), "garbage"));
	}

	@Test
	void unauthorizedProjectIsRejected() {
		assertThrows(io.github.leohou0430.llmgw.catalog.CatalogAccessException.class, () -> catalog.list("forbidden"));
		assertThrows(io.github.leohou0430.llmgw.catalog.CatalogAccessException.class,
				() -> catalog.create(entry("f1", "forbidden", "file")));
	}

}
