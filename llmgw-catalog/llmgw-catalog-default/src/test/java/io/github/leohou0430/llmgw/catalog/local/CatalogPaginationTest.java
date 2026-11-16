package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogCursorException;
import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.6 seam: stable, signed cursor pagination. Ordering is creation sequence; the
 * first page fixes a high-water mark so entries created mid-traversal never appear;
 * the cursor is signed and bound to tenant/project/filter; page size is bounded; and
 * stale entries remain readable with their last-synced timestamp.
 */
class CatalogPaginationTest {

    private static ResourceEntry entry(String gatewayId, String type, long lastSyncedAt) {
        return new ResourceEntry(gatewayId, "p1", type, "up-" + gatewayId, "route-a", "deepseek",
                "cfg-1", 1000L, "pending", Map.of(), lastSyncedAt, false);
    }

    private static List<String> ids(CatalogPage page) {
        return page.items().stream().map(ResourceEntry::gatewayId).toList();
    }

    @Test
    void stableTraversalExcludesEntriesCreatedAfterFirstPage() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        catalog.create(entry("gw-1", "batch", 0L));
        catalog.create(entry("gw-2", "batch", 0L));
        catalog.create(entry("gw-3", "batch", 0L));

        CatalogPage first = catalog.page("t1", "p1", PageRequest.of(2), null);
        assertEquals(List.of("gw-1", "gw-2"), ids(first));
        assertTrue(first.hasMore());

        // A new entry created after the traversal began must not leak into this run.
        catalog.create(entry("gw-4", "batch", 0L));

        CatalogPage second = catalog.page("t1", "p1", PageRequest.of(2), first.nextCursor());
        assertEquals(List.of("gw-3"), ids(second));
        assertNull(second.nextCursor());
    }

    @Test
    void tamperedCursorIsRejected() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        catalog.create(entry("gw-1", "batch", 0L));
        catalog.create(entry("gw-2", "batch", 0L));
        String cursor = catalog.page("t1", "p1", PageRequest.of(1), null).nextCursor();

        char last = cursor.charAt(cursor.length() - 1);
        String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThrows(CatalogCursorException.class,
                () -> catalog.page("t1", "p1", PageRequest.of(1), tampered));
    }

    @Test
    void cursorIsBoundToTenantProjectAndFilter() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        catalog.create(entry("gw-1", "batch", 0L));
        catalog.create(entry("gw-2", "batch", 0L));
        String cursor = catalog.page("t1", "p1", PageRequest.of(1, "batch"), null).nextCursor();

        assertThrows(CatalogCursorException.class,
                () -> catalog.page("t2", "p1", PageRequest.of(1, "batch"), cursor), "wrong tenant");
        assertThrows(CatalogCursorException.class,
                () -> catalog.page("t1", "p2", PageRequest.of(1, "batch"), cursor), "wrong project");
        assertThrows(CatalogCursorException.class,
                () -> catalog.page("t1", "p1", PageRequest.of(1, "video"), cursor), "wrong filter");
    }

    @Test
    void pageSizeIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(0, null));
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(PageRequest.MAX_LIMIT + 1, null));
        assertEquals(PageRequest.MAX_LIMIT, PageRequest.of(PageRequest.MAX_LIMIT).limit());
    }

    @Test
    void staleEntryRemainsReadableWithLastSyncedAt() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        catalog.create(entry("gw-1", "batch", 12345L));

        CatalogPage page = catalog.page("t1", "p1", PageRequest.of(10), null);
        assertFalse(page.items().isEmpty());
        assertEquals(12345L, page.items().get(0).lastSyncedAt(),
                "last-observed time must survive so staleness is caller-visible");
    }
}