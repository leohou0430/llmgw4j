package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.catalog.TaskCanceller;
import io.github.leohou0430.llmgw.catalog.TaskCatalog;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Task directory over async resources only (batch/video). It holds no executor, queue
 * or recovery scheduler: registering and paging are directory operations, and cancel
 * is a pass-through to the entry's fixed upstream source — the entry is marked
 * cancelled only once the upstream confirms. It never impersonates a real provider's
 * task execution.
 */
public final class LocalTaskCatalog implements TaskCatalog {
    private static final Set<String> ASYNC_TYPES = Set.of("batch", "video");

    private final ResourceCatalog catalog;
    private final LongSupplier clock;

    public LocalTaskCatalog(ResourceCatalog catalog) {
        this(catalog, System::currentTimeMillis);
    }

    public LocalTaskCatalog(ResourceCatalog catalog, LongSupplier clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResourceEntry register(ResourceEntry task) {
        Objects.requireNonNull(task, "task");
        if (!ASYNC_TYPES.contains(task.type())) {
            throw new IllegalArgumentException("not an async task type: " + task.type());
        }
        return catalog.create(task);
    }

    @Override
    public Optional<ResourceEntry> get(String projectId, String gatewayId) {
        return catalog.get(projectId, gatewayId);
    }

    @Override
    public boolean cancel(String projectId, String gatewayId, TaskCanceller canceller) {
        Objects.requireNonNull(canceller, "canceller");
        Optional<ResourceEntry> found = catalog.get(projectId, gatewayId);
        if (found.isEmpty()) {
            return false;
        }
        ResourceEntry entry = found.get();
        canceller.cancel(entry.sourceRoute(), entry.upstreamId());
        catalog.create(entry.withStatus("cancelled", clock.getAsLong()));
        return true;
    }

    @Override
    public CatalogPage page(String tenantId, String projectId, PageRequest request, String cursor) {
        return catalog.page(tenantId, projectId, request, cursor);
    }
}