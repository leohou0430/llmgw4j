package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CreationOutcome;
import io.github.leohou0430.llmgw.catalog.CreationRequest;
import io.github.leohou0430.llmgw.catalog.ReconciliationEvent;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.catalog.UpstreamCreator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.5 seam: creation consistency. The upstream object is created exactly once; a
 * confirmed result requires the catalog commit to succeed; a catalog-commit failure
 * after an upstream success yields an UNKNOWN result plus a sanitized reconciliation
 * event — never a false success and never an automatic resend.
 */
class CreationServiceTest {

    private static CreationRequest request() {
        return new CreationRequest("p1", "gw-1", "batch", "route-a", "deepseek", "cfg-1",
                Map.of("secret", "TOPSECRET-VALUE"));
    }

    private static ResourceCatalog catalogThatFailsOnCreate() {
        return new ResourceCatalog() {
            @Override public ResourceEntry create(ResourceEntry entry) {
                throw new IllegalStateException("catalog db down");
            }
            @Override public Optional<ResourceEntry> get(String projectId, String gatewayId) {
                return Optional.empty();
            }
            @Override public boolean delete(String projectId, String gatewayId) {
                return false;
            }
            @Override public List<ResourceEntry> list(String projectId) {
                return List.of();
            }
        };
    }

    @Test
    void confirmedWhenUpstreamAndCatalogSucceed() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        List<ReconciliationEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        CreationService service = new CreationService(catalog,
                request -> { calls.incrementAndGet(); return "up-123"; }, events::add, () -> 999L);

        CreationOutcome outcome = service.create(request());

        CreationOutcome.Confirmed confirmed = assertInstanceOf(CreationOutcome.Confirmed.class, outcome);
        assertEquals("up-123", confirmed.entry().upstreamId());
        assertEquals(1, calls.get());
        assertTrue(events.isEmpty(), "confirmed creation emits no reconciliation event");
        assertTrue(catalog.get("p1", "gw-1").isPresent());
    }

    @Test
    void unknownAndSanitizedEventWhenCatalogCommitFails() {
        List<ReconciliationEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        CreationService service = new CreationService(catalogThatFailsOnCreate(),
                request -> { calls.incrementAndGet(); return "up-123"; }, events::add, () -> 999L);

        CreationOutcome outcome = service.create(request());

        CreationOutcome.Unknown unknown = assertInstanceOf(CreationOutcome.Unknown.class, outcome);
        assertEquals("up-123", unknown.upstreamId());
        assertEquals(1, events.size());
        ReconciliationEvent event = events.get(0);
        assertEquals("p1", event.projectId());
        assertEquals("gw-1", event.gatewayId());
        assertEquals("up-123", event.upstreamId());
        assertEquals(999L, event.observedAt());
        assertFalse(event.toString().contains("TOPSECRET-VALUE"),
                "reconciliation event must not leak metadata values / secrets");
        assertEquals(1, calls.get(), "a create without an idempotency contract must not auto-retry");
    }

    @Test
    void failedWhenUpstreamFails() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        List<ReconciliationEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        CreationService service = new CreationService(catalog, request -> {
            calls.incrementAndGet();
            throw new UpstreamCreator.UpstreamCreationException("upstream down");
        }, events::add, () -> 999L);

        CreationOutcome outcome = service.create(request());

        assertInstanceOf(CreationOutcome.Failed.class, outcome);
        assertEquals(1, calls.get());
        assertTrue(events.isEmpty());
        assertTrue(catalog.get("p1", "gw-1").isEmpty(), "nothing committed when the upstream fails");
    }
}