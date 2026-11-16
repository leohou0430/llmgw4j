package io.github.leohou0430.llmgw.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Project-scoped catalog SPI. Every method takes the caller's {@code projectId} and
 * must run {@link ProjectAuthorizer#requireAuthorized} before touching the fixed
 * source; an entry owned by a different project is invisible (cross-project
 * isolation). Implementations never re-resolve {@code sourceRoute} at read time.
 */
public interface ResourceCatalog {

    /** Stores a new entry for {@code entry.projectId()} after the authorization check. */
    ResourceEntry create(ResourceEntry entry);

    Optional<ResourceEntry> get(String projectId, String gatewayId);

    boolean delete(String projectId, String gatewayId);

    List<ResourceEntry> list(String projectId);

    /**
     * Stable, cursor-paginated read. Implementations that do not paginate may rely on
     * this default. The signed cursor binds the tenant, project, filter and the first
     * page's high-water mark so newly created entries never leak into an in-flight
     * traversal, and a tampered/foreign cursor is rejected.
     */
    default CatalogPage page(String tenantId, String projectId, PageRequest request, String cursor) {
        throw new UnsupportedOperationException("pagination is not supported");
    }
}