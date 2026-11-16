package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.7 seam: the task catalog tracks async resources only (batch/video), pages them,
 * and cancels by forwarding to the fixed upstream source — marking the entry cancelled
 * only after the upstream confirms. It is not an executor/queue and does not
 * impersonate a real provider; a fake canceller stands in for the controlled upstream.
 */
class TaskCatalogTest {

    private static ResourceEntry task(String gatewayId, String type, String sourceRoute, String upstreamId) {
        return new ResourceEntry(gatewayId, "p1", type, upstreamId, sourceRoute, "deepseek", "cfg-1",
                1000L, "pending", Map.of(), 0L, false);
    }

    private static LocalTaskCatalog catalog() {
        return new LocalTaskCatalog(new LocalResourceCatalog(project -> true));
    }

    @Test
    void registerAcceptsAsyncTypes() {
        LocalTaskCatalog catalog = catalog();
        catalog.register(task("gw-1", "batch", "route-a", "up-1"));
        catalog.register(task("gw-2", "video", "route-a", "up-2"));
        assertTrue(catalog.get("p1", "gw-1").isPresent());
        assertTrue(catalog.get("p1", "gw-2").isPresent());
    }

    @Test
    void registerRejectsNonAsyncType() {
        LocalTaskCatalog catalog = catalog();
        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(task("gw-1", "chat", "route-a", "up-1")));
    }

    @Test
    void cancelForwardsToFixedSourceThenMarksCancelled() {
        LocalTaskCatalog catalog = catalog();
        catalog.register(task("gw-1", "batch", "route-a", "up-1"));

        List<String> seen = new ArrayList<>();
        boolean cancelled = catalog.cancel("p1", "gw-1", (source, upstreamId) -> seen.add(source + "/" + upstreamId));

        assertTrue(cancelled);
        assertEquals(List.of("route-a/up-1"), seen, "cancel must reach the entry's fixed source and upstream id");
        assertEquals("cancelled", catalog.get("p1", "gw-1").orElseThrow().observableStatus());
    }

    @Test
    void cancelUpstreamFailureDoesNotMarkCancelled() {
        LocalTaskCatalog catalog = catalog();
        catalog.register(task("gw-1", "batch", "route-a", "up-1"));

        assertThrows(RuntimeException.class, () -> catalog.cancel("p1", "gw-1", (source, upstreamId) -> {
            throw new RuntimeException("upstream rejected cancel");
        }));
        assertEquals("pending", catalog.get("p1", "gw-1").orElseThrow().observableStatus(),
                "entry must not be marked cancelled unless the upstream confirms");
    }

    @Test
    void cancelUnknownTaskReturnsFalse() {
        assertFalse(catalog().cancel("p1", "missing", (source, upstreamId) -> { }));
    }

    @Test
    void pagesAsyncTasks() {
        LocalTaskCatalog catalog = catalog();
        catalog.register(task("gw-1", "batch", "route-a", "up-1"));
        catalog.register(task("gw-2", "video", "route-a", "up-2"));

        CatalogPage page = catalog.page("t1", "p1", PageRequest.of(10), null);
        assertEquals(List.of("gw-1", "gw-2"),
                page.items().stream().map(ResourceEntry::gatewayId).toList());
    }
}