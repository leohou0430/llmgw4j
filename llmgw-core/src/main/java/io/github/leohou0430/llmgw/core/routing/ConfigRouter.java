package io.github.leohou0430.llmgw.core.routing;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;

import java.util.List;
import java.util.Objects;

/**
 * Router that delegates to a hot-swappable {@link RouteConfigSource}.
 * <p>v10: the immutable route table used to live in final fields here, which
 * made every config change require a process restart. Now each call reads one
 * atomic reference; the table itself is built and validated off the hot path.
 * The {@code Router} SPI is unchanged, so neither data plane had to be touched.
 */
public class ConfigRouter implements Router {

    private final RouteConfigSource source;

    /** Backwards-compatible: build the default in-memory source from properties. */
    public ConfigRouter(GatewayProperties properties) {
        this(new InMemoryRouteConfigSource(properties));
    }

    public ConfigRouter(RouteConfigSource source) {
        this.source = Objects.requireNonNull(source, "routeConfigSource");
    }

    public RouteConfigSource source() { return source; }

    /** Current immutable snapshot (admin / tests). */
    public RouteTable snapshot() { return source.current(); }

    @Override
    public Route resolve(String model) {
        return source.current().resolve(model);
    }

    @Override
    public List<Route> fallbacks(Route primary) {
        return source.current().fallbacks(primary);
    }

    @Override
    public List<Route> all() {
        return source.current().routes();
    }
}
