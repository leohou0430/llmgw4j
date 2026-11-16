package io.github.leohou0430.llmgw.catalog;

import java.util.Map;
import java.util.Objects;

/**
 * One catalog entry owned by a project. {@code sourceRoute} is fixed at creation:
 * a later gateway default-route switch must not re-point an existing resource. The
 * upstream {@code id}/{@code configVersion} identify the exact upstream generation;
 * {@code observableStatus}/{@code lastSyncedAt} carry the last observed upstream
 * state (stale when the upstream is unreachable). {@code deleted} is the tombstone.
 */
public record ResourceEntry(
        String gatewayId,
        String projectId,
        String type,
        String upstreamId,
        String sourceRoute,
        String provider,
        String configVersion,
        long createdAt,
        String observableStatus,
        Map<String, String> metadata,
        long lastSyncedAt,
        boolean deleted) {

    public ResourceEntry {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceRoute, "sourceRoute");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ResourceEntry withStatus(String status, long syncedAt) {
        return new ResourceEntry(gatewayId, projectId, type, upstreamId, sourceRoute, provider,
                configVersion, createdAt, status, metadata, syncedAt, deleted);
    }

    public ResourceEntry asDeleted() {
        return new ResourceEntry(gatewayId, projectId, type, upstreamId, sourceRoute, provider,
                configVersion, createdAt, observableStatus, metadata, lastSyncedAt, true);
    }
}