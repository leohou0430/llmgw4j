package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.ResourceLifecycle;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLifecycleTest {
    private ApiKeyRegistry registry() {
        var keys = List.of(key("a", "t1", "p1"), key("b", "t1", "p1"), key("c", "t1", "p2"), key("d", "t2", "p1"));
        return new ApiKeyRegistry() {
            public Optional<ApiKey> find(String token) { return keys.stream().filter(k -> k.id().equals(token)).findFirst(); }
            public Collection<ApiKey> all() { return keys; }
        };
    }
    private ApiKeyRegistry.ApiKey key(String id, String tenant, String project) {
        return new ApiKeyRegistry.ApiKey(id, id, 0, Set.of(), 0, "", 0,
                Map.of("llmgw.tenantId", tenant, "llmgw.projectId", project));
    }
    @Test void completedBatchRegistersOutputFileOnItsOriginalSource() {
        var resources = new ResourceLifecycle(new LocalResourceCatalog(project -> true), registry());
        var route = new Route("r", "*", "*", "*", "http://origin/v1", "secret", "openai", 1, List.of());
        resources.created("a", ProtocolKind.BATCHES, route, "{\"id\":\"batch-1\",\"status\":\"in_progress\"}");
        resources.observed("b", ProtocolKind.BATCHES, "batch-1", route,
                "{\"id\":\"batch-1\",\"status\":\"completed\",\"output_file_id\":\"output-1\"}", false);
        assertEquals(route, resources.route("b", ProtocolKind.FILES, "output-1"));
        assertNull(resources.route("c", ProtocolKind.FILES, "output-1"));
    }
    @Test void admissionScopeSurvivesKeyRevocationDuringUpload() {
        var catalog = new LocalResourceCatalog(project -> true);
        ApiKeyRegistry revoked = new ApiKeyRegistry() {
            public Optional<ApiKey> find(String token) { return Optional.empty(); }
            public Collection<ApiKey> all() { throw new AssertionError("inflight scope must not query revoked registry"); }
        };
        var route = new Route("r", "*", "*", "*", "http://origin/v1", "secret", "openai", 1, List.of());
        var decision = io.github.leohou0430.llmgw.core.governance.GatewayDecision.allow("revoked", ParsedRequest.bodyless(ProtocolKind.FILES), route)
                .withScope("t1", "p1").withReservation("reservation").withBudgetReserved(10).withGroup("g", 5).withRoute(route);
        var inflight = new ResourceLifecycle(catalog, revoked).scoped(decision);
        inflight.created("revoked", ProtocolKind.FILES, route, "{\"id\":\"completed-after-revoke\"}");
        assertEquals("t1", decision.tenantId());
        assertEquals("p1", catalog.list("p1").getFirst().projectId());
        assertNotNull(inflight.route("revoked", ProtocolKind.FILES, "completed-after-revoke"));
    }
    @Test void projectSharingIsolationSnapshotAndProviderPagination() {
        var catalog = new LocalResourceCatalog(project -> true);
        var resources = new ResourceLifecycle(catalog, registry());
        var origin = new Route("same-id", "*", "*", "*", "http://origin/v1", "secret", "openai", 1, List.of());
        resources.created("a", ProtocolKind.FILES, origin, "{\"id\":\"f1\",\"object\":\"file\",\"filename\":\"one\"}");
        resources.created("a", ProtocolKind.FILES, origin, "{\"id\":\"f2\",\"object\":\"file\"}");
        assertEquals(origin, resources.route("b", ProtocolKind.FILES, "f1"));
        assertNull(resources.route("c", ProtocolKind.FILES, "f1"));
        assertNull(resources.route("d", ProtocolKind.FILES, "f1"));
        String first = resources.list("b", ProtocolKind.FILES, "1", null);
        var page = ProtocolCodec.tryParse(first);
        assertTrue(page.path("has_more").asBoolean());
        assertEquals("f1", page.path("data").get(0).path("id").asText());
        assertFalse(first.contains("secret")); assertFalse(first.contains("sourceRoute"));
        assertEquals("f2", ProtocolCodec.tryParse(resources.list("b", ProtocolKind.FILES, "1", "f1"))
                .path("data").get(0).path("id").asText());
        resources.created("a", ProtocolKind.FILES, origin, "{\"id\":\"new\"}");
        var second = ProtocolCodec.tryParse(resources.list("b", ProtocolKind.FILES, "50", page.path("next_cursor").asText()));
        assertEquals(1, second.path("data").size());
        resources.observed("b", ProtocolKind.FILES, "f1", origin, "{}", true);
        assertNull(resources.route("a", ProtocolKind.FILES, "f1"));
    }
    @Test void referencedFilesKeepOriginalUpstreamWhenDefaultRouteChanges() {
        var resources = new ResourceLifecycle(new LocalResourceCatalog(project -> true), registry());
        var old = new Route("route", "*", "*", "*", "http://old/v1", "old-key", "openai", 1, List.of("fallback"));
        var current = new Route("route", "*", "*", "*", "http://new/v1", "new-key", "openai", 1, List.of());
        resources.created("a", ProtocolKind.FILES, old, "{\"id\":\"f1\"}");
        var parsed = Protocols.of(ProtocolKind.BATCHES).parse("{\"input_file_id\":\"f1\",\"endpoint\":\"/v1/chat/completions\",\"completion_window\":\"24h\"}");
        var pinned = resources.referencedRoute(parsed, "b", current);
        assertEquals(old.targetUrl(), pinned.targetUrl());
        assertEquals(old.apiKey(), pinned.apiKey());
        assertTrue(pinned.fallbackIds().isEmpty());
        assertTrue(resources.ownsFiles(parsed, "b", pinned));
        assertFalse(resources.ownsFiles(parsed, "c", pinned));
    }
}
