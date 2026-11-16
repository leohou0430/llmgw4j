package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RouteVersion;
import io.github.leohou0430.llmgw.control.RouteVersionStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentNavigableMap;

/** In-memory, append-only route-version store (durable SQLite backing is a follow-up). */
public final class InMemoryRouteVersionStore implements RouteVersionStore {
    private final ConcurrentNavigableMap<Long, RouteVersion> versions = new ConcurrentSkipListMap<>();

    @Override
    public Optional<RouteVersion> latest() {
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    @Override
    public Optional<RouteVersion> find(long version) {
        return Optional.ofNullable(versions.get(version));
    }

    @Override
    public List<RouteVersion> all() {
        return List.copyOf(versions.values());
    }

    @Override
    public void append(RouteVersion version) {
        if (versions.putIfAbsent(version.version(), version) != null)
            throw new io.github.leohou0430.llmgw.control.VersionConflictException("route version already exists");
    }
}
