package io.github.leohou0430.llmgw.core.routing;

import java.util.List;

/**
 * SPI for the routing configuration source (v10).
 * <p>The core depends only on this interface; the default implementation keeps
 * an immutable {@link RouteTable} in memory, while a deployment can plug in a
 * config-center listener that simply returns its own latest snapshot.
 *
 * <p>{@link #replace(List)} is optional: a read-only source (e.g. one driven
 * by an external watcher) returns -1 and the admin reload endpoint reports a
 * conflict instead of silently doing nothing.
 */
public interface RouteConfigSource {

    /** Never null. Implementations must return an immutable, fully built table. */
    RouteTable current();

    /**
     * Atomically publish a new table built from the supplied routes.
     * @return the new version, or -1 when this source does not accept pushes
     */
    default long replace(List<Route> routes) {
        return -1;
    }
}
