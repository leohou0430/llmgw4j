package io.github.leohou0430.llmgw.core.routing;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Default in-memory, hot-swappable route source. Zero external dependencies. */
public final class InMemoryRouteConfigSource implements RouteConfigSource {

    private final AtomicReference<RouteTable> table = new AtomicReference<>();

    public InMemoryRouteConfigSource(GatewayProperties properties) {
        this(fromProperties(properties));
    }

    public InMemoryRouteConfigSource(List<Route> routes) {
        table.set(RouteTable.of(routes, 0));
    }

    @Override
    public RouteTable current() {
        return table.get();
    }

    /**
     * Build + validate the new table BEFORE the CAS, so a bad config can never
     * leave the gateway in a partial state. In-flight requests keep the Route
     * object they resolved earlier; only subsequent requests see the new table.
     */
    @Override
    public long replace(List<Route> routes) {
        for (;;) {
            RouteTable previous = table.get();
            RouteTable next = RouteTable.of(routes, previous.version() + 1);
            if (table.compareAndSet(previous, next)) {
                return next.version();
            }
        }
    }

    /** Startup conversion, also exposed for admin-side default URL/key reuse. */
    public static List<Route> fromProperties(GatewayProperties properties) {
        List<Route> parsed = new ArrayList<>();
        String defaultUrl = properties.getUpstream().getBaseUrl();
        String defaultKey = properties.getUpstream().getApiKey();
        for (GatewayProperties.RouteDef def : properties.getRoutes()) {
            String url = def.getTargetUrl().isBlank() ? defaultUrl : def.getTargetUrl();
            String key = def.getApiKey().isBlank() ? defaultKey : def.getApiKey();
            parsed.add(new Route(def.getId(), def.getProtocol(), def.getModel(), def.getTargetModel(),
                    url, key, Math.max(1, def.getWeight()), List.copyOf(def.getFallbacks())));
        }
        if (parsed.isEmpty()) {
            parsed.add(new Route("default", "*", "*", "", defaultUrl, defaultKey, 1, List.of()));
        }
        return parsed;
    }
}
