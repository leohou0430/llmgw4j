package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RouteVersion;
import io.github.leohou0430.llmgw.control.RouteVersionStore;
import io.github.leohou0430.llmgw.control.VersionConflictException;

import java.util.Objects;

/**
 * Route-version publisher: a draft is validated then published as an immutable version and
 * activated in this process. Publishing/rolling back requires the caller's expected version
 * (optimistic concurrency); a rollback creates a NEW version referencing the target config
 * rather than rewriting history. On construction the service recovers the committed version
 * from the store (startup recovery). Single-process activation only; cross-replica
 * confirmation is not claimed.
 */
public final class RouteVersionService {
    private final RouteVersionStore store;
    private String draft;
    private long activeVersion;
    private String activeConfig;

    public RouteVersionService(RouteVersionStore store) {
        this.store = Objects.requireNonNull(store, "store");
        store.latest().ifPresent(version -> {
            activeVersion = version.version();
            activeConfig = version.config();
        });
    }

    public synchronized void saveDraft(String config) {
        if (config == null || config.isBlank()) {
            throw new IllegalArgumentException("draft config is required");
        }
        this.draft = config;
    }

    public synchronized RouteVersion publish(long expectedVersion) {
        if (draft == null) {
            throw new IllegalStateException("no draft to publish");
        }
        if (expectedVersion != activeVersion) {
            throw new VersionConflictException(
                    "expected version " + expectedVersion + " but latest is " + activeVersion);
        }
        RouteVersion version = new RouteVersion(activeVersion + 1, draft, 0L);
        store.append(version);
        activate(version);
        draft = null;
        return version;
    }

    public synchronized RouteVersion rollback(long targetVersion, long expectedVersion) {
        if (expectedVersion != activeVersion) {
            throw new VersionConflictException(
                    "expected version " + expectedVersion + " but latest is " + activeVersion);
        }
        RouteVersion target = store.find(targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown version " + targetVersion));
        RouteVersion version = new RouteVersion(activeVersion + 1, target.config(), targetVersion);
        store.append(version);
        activate(version);
        return version;
    }

    private void activate(RouteVersion version) {
        activeVersion = version.version();
        activeConfig = version.config();
    }

    public long activeVersion() {
        return activeVersion;
    }

    public String activeConfig() {
        return activeConfig;
    }
}