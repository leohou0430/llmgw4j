package io.github.leohou0430.llmgw.core.routing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskRouteIsolationTest {
    @Test void sameResourceIdIsIsolatedByTenantAndProtocol() {
        TaskRouteSticky routes = new InMemoryTaskRouteSticky(100, 60);
        routes.bind("a", "files", "same", "route-a");
        routes.bind("b", "files", "same", "route-b");
        assertEquals("route-a", routes.resolve("a", "files", "same"));
        assertEquals("route-b", routes.resolve("b", "files", "same"));
        assertNull(routes.resolve("c", "files", "same"));
        assertNull(routes.resolve("a", "batches", "same"));
    }
    @Test void legacyUnownedEntriesCannotGrantAccess() {
        TaskRouteSticky routes = new InMemoryTaskRouteSticky(100, 60);
        routes.bind("old", "upstream");
        assertNull(routes.resolve("tenant", "files", "old"));
    }
}
