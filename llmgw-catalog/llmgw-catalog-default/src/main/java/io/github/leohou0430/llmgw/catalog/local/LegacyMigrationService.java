package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.LegacyResource;
import io.github.leohou0430.llmgw.catalog.MigrationApproval;
import io.github.leohou0430.llmgw.catalog.MigrationAuditSink;
import io.github.leohou0430.llmgw.catalog.MigrationPreview;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Migrates legacy-space resources into tenant/project catalogs only under an explicit,
 * human-reviewed approval. Legacy resources stay in their own space (this service never
 * deletes or imports by scanning a provider account). Missing ownership is rejected, not
 * guessed; a target-project id collision is a conflict that is rejected, never merged;
 * every decision is audited; and the compatibility lookup is restricted to the original
 * legacy owner.
 */
public final class LegacyMigrationService {
    private final ResourceCatalog catalog;
    private final List<LegacyResource> legacySpace;
    private final MigrationAuditSink audit;
    private final LongSupplier clock;

    public LegacyMigrationService(ResourceCatalog catalog, List<LegacyResource> legacySpace,
                                  MigrationAuditSink audit, LongSupplier clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.legacySpace = List.copyOf(Objects.requireNonNull(legacySpace, "legacySpace"));
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MigrationPreview preview(List<MigrationApproval> approvals) {
        return plan(approvals, false);
    }

    public MigrationPreview apply(List<MigrationApproval> approvals) {
        return plan(approvals, true);
    }

    private MigrationPreview plan(List<MigrationApproval> approvals, boolean write) {
        Map<String, MigrationApproval> approvedByOwner = new HashMap<>();
        for (MigrationApproval approval : approvals) {
            if (approval.approved()) {
                approvedByOwner.put(approval.legacyOwner(), approval);
            }
        }

        List<ResourceEntry> toMigrate = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        long now = clock.getAsLong();
        String acceptedAction = write ? "migrated" : "previewed";

        for (LegacyResource resource : legacySpace) {
            MigrationApproval approval = approvedByOwner.get(resource.legacyOwner());
            if (approval == null) {
                rejected.add(resource.legacyId());
                audit.record("rejected-no-approval", resource.legacyId(), resource.legacyOwner());
                continue;
            }
            if (catalog.get(approval.projectId(), resource.legacyId()).isPresent()) {
                conflicts.add(resource.legacyId());
                audit.record("conflict", resource.legacyId(), approval.projectId());
                continue;
            }
            ResourceEntry entry = new ResourceEntry(resource.legacyId(), approval.projectId(), resource.type(),
                    resource.upstreamId(), resource.sourceRoute(), resource.provider(), resource.configVersion(),
                    now, "migrated", resource.metadata(), 0L, false);
            toMigrate.add(entry);
            if (write) {
                catalog.create(entry);
            }
            audit.record(acceptedAction, resource.legacyId(), approval.projectId());
        }
        return new MigrationPreview(toMigrate, rejected, conflicts);
    }

    public Optional<LegacyResource> lookupLegacy(String legacyOwner, String legacyId) {
        return legacySpace.stream()
                .filter(resource -> resource.legacyOwner().equals(legacyOwner)
                        && resource.legacyId().equals(legacyId))
                .findFirst();
    }
}