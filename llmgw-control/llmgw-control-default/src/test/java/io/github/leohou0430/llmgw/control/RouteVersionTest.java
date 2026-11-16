package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RouteVersion;
import io.github.leohou0430.llmgw.control.VersionConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.5 seam: draft → validate → immutable publish → activate; expected-version guards
 * concurrent overwrite; rollback creates a NEW version referencing the target (history is
 * never rewritten); a service started on a persisted store recovers the active version.
 */
class RouteVersionTest {

    @Test
    void publishIncrementsAndActivates() {
        RouteVersionService service = new RouteVersionService(new InMemoryRouteVersionStore());
        service.saveDraft("routes: v1");
        RouteVersion published = service.publish(0L);
        assertEquals(1L, published.version());
        assertEquals(1L, service.activeVersion());
        assertEquals("routes: v1", service.activeConfig());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        RouteVersionService service = new RouteVersionService(new InMemoryRouteVersionStore());
        service.saveDraft("cfg1");
        service.publish(0L);
        service.saveDraft("cfg2");
        assertThrows(VersionConflictException.class, () -> service.publish(0L));
    }

    @Test
    void rollbackCreatesNewVersionReferencingTarget() {
        InMemoryRouteVersionStore store = new InMemoryRouteVersionStore();
        RouteVersionService service = new RouteVersionService(store);
        service.saveDraft("cfg1");
        service.publish(0L);
        service.saveDraft("cfg2");
        service.publish(1L);

        RouteVersion rolledBack = service.rollback(1L, 2L);

        assertEquals(3L, rolledBack.version());
        assertEquals("cfg1", rolledBack.config(), "rollback references the target's config");
        assertEquals(1L, rolledBack.baseVersion());
        assertEquals(3L, store.all().size(), "history (v1,v2,v3) is preserved, not rewritten");
        assertEquals("cfg1", service.activeConfig());
    }

    @Test
    void startupRecoversCommittedVersion() {
        InMemoryRouteVersionStore store = new InMemoryRouteVersionStore();
        store.append(new RouteVersion(1L, "a", 0L));
        store.append(new RouteVersion(2L, "b", 0L));

        RouteVersionService recovered = new RouteVersionService(store);

        assertEquals(2L, recovered.activeVersion());
        assertEquals("b", recovered.activeConfig());
    }

    @Test
    void publishWithoutDraftIsRejected() {
        RouteVersionService service = new RouteVersionService(new InMemoryRouteVersionStore());
        assertThrows(IllegalStateException.class, () -> service.publish(0L));
    }
}