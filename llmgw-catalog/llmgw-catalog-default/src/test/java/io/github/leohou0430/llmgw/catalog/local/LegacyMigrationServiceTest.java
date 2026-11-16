package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.LegacyResource;
import io.github.leohou0430.llmgw.catalog.MigrationApproval;
import io.github.leohou0430.llmgw.catalog.MigrationPreview;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.9 seam: legacy resources stay in their own space until an explicit reviewed
 * approval maps them into a tenant/project. Missing ownership is never guessed,
 * conflicts are rejected (no auto-merge), every decision is audited, and a legacy
 * lookup is restricted to the original owner.
 */
class LegacyMigrationServiceTest {

    private static LegacyResource legacy(String owner, String legacyId) {
        return new LegacyResource(owner, legacyId, "batch", "route-a", "up-" + legacyId,
                "deepseek", "cfg-1", Map.of());
    }

    private static MigrationApproval approval(String owner, String project, boolean approved) {
        return new MigrationApproval(owner, "ten-1", project, "reviewer-1", approved);
    }

    @Test
    void previewDoesNotApply() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        LegacyMigrationService service = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);

        MigrationPreview preview = service.preview(List.of(approval("old1", "p1", true)));

        assertEquals(1, preview.toMigrate().size());
        assertEquals("p1", preview.toMigrate().get(0).projectId());
        assertTrue(catalog.get("p1", "r1").isEmpty(), "preview must not write the catalog");
    }

    @Test
    void applyRequiresExplicitApproval() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        LegacyMigrationService noApproval = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);
        MigrationPreview rejected = noApproval.apply(List.of());
        assertEquals(List.of("r1"), rejected.rejected());
        assertTrue(catalog.get("p1", "r1").isEmpty());

        LegacyMigrationService approved = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);
        approved.apply(List.of(approval("old1", "p1", true)));
        assertTrue(catalog.get("p1", "r1").isPresent(), "approved legacy resource is shared into its project");
    }

    @Test
    void conflictIsRejectedWithoutAutoMerge() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        ResourceEntry existing = new ResourceEntry("r1", "p1", "batch", "up-old", "route-a", "deepseek",
                "cfg-1", 1L, "existing", Map.of(), 0L, false);
        catalog.create(existing);

        LegacyMigrationService service = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);
        MigrationPreview preview = service.apply(List.of(approval("old1", "p1", true)));

        assertEquals(List.of("r1"), preview.conflicts());
        assertEquals("existing", catalog.get("p1", "r1").orElseThrow().observableStatus(),
                "existing entry must not be overwritten/merged");
    }

    @Test
    void missingOwnershipIsNotGuessed() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        LegacyMigrationService service = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);

        MigrationPreview preview = service.apply(List.of(approval("someone-else", "p1", true)));

        assertEquals(List.of("r1"), preview.rejected());
        assertTrue(catalog.list("p1").isEmpty());
    }

    @Test
    void auditRecordsEveryDecision() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        List<String> audit = new ArrayList<>();
        LegacyMigrationService service = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> audit.add(action + ":" + id), () -> 999L);

        service.apply(List.of(approval("old1", "p1", true)));

        assertTrue(audit.contains("migrated:r1"), "accepted decision is audited");
    }

    @Test
    void legacyLookupIsOwnerRestricted() {
        LocalResourceCatalog catalog = new LocalResourceCatalog(project -> true);
        LegacyMigrationService service = new LegacyMigrationService(
                catalog, List.of(legacy("old1", "r1")), (action, id, detail) -> { }, () -> 999L);

        assertTrue(service.lookupLegacy("old1", "r1").isPresent());
        assertTrue(service.lookupLegacy("other", "r1").isEmpty(), "compatibility entry limited to the original owner");
    }
}