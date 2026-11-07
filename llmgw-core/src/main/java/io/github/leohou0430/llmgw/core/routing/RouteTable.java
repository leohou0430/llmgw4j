package io.github.leohou0430.llmgw.core.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable routing snapshot (v10).
 * <p>A config change swaps ONE atomic reference, so routes / byId / fallback
 * can never be observed half-updated and in-flight SSE streams never need to
 * be drained or re-resolved. Budget on the hot path is a single volatile read.
 */
public final class RouteTable {

    private final List<Route> routes;
    private final Map<String, Route> byId;
    private final Route fallback;
    private final long version;

    private RouteTable(List<Route> routes, Map<String, Route> byId,
                       Route fallback, long version) {
        this.routes = routes;
        this.byId = byId;
        this.fallback = fallback;
        this.version = version;
    }

    /** Validate, sort by specificity then weight, and freeze. */
    public static RouteTable of(List<Route> source, long version) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("at least one route is required");
        }
        List<Route> parsed = new ArrayList<>(source.size());
        Map<String, Route> byId = new HashMap<>();
        for (Route route : source) {
            if (route == null || route.id() == null || route.id().isBlank()) {
                throw new IllegalArgumentException("route id must not be blank");
            }
            if (route.model() == null || route.model().isBlank()) {
                throw new IllegalArgumentException("route model must not be blank: " + route.id());
            }
            if (byId.putIfAbsent(route.id(), route) != null) {
                throw new IllegalArgumentException("duplicate route id: " + route.id());
            }
            parsed.add(route);
        }
        // An exact model match must beat a "*" wildcard regardless of weight;
        // weight only orders routes within the same specificity class.
        parsed.sort(Comparator
                .comparingInt((Route r) -> "*".equals(r.model()) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(Route::weight).reversed()));
        List<Route> frozen = List.copyOf(parsed);
        return new RouteTable(frozen, Map.copyOf(byId), frozen.get(0), version);
    }

    /** Exact model matches always beat the "*" wildcard; same class competes by weight. */
    public Route resolve(String model) {
        List<Route> exact = null;
        for (Route route : routes) {
            if (!"*".equals(route.model()) && route.model().equals(model)) {
                if (exact == null) {
                    exact = new ArrayList<>(2);
                }
                exact.add(route);
            }
        }
        if (exact != null && !exact.isEmpty()) {
            return weightedPick(exact);
        }
        List<Route> wildcards = new ArrayList<>();
        for (Route route : routes) {
            if ("*".equals(route.model())) {
                wildcards.add(route);
            }
        }
        return wildcards.isEmpty() ? fallback : weightedPick(wildcards);
    }

    private static Route weightedPick(List<Route> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        long total = 0;
        for (Route route : candidates) {
            total += Math.max(1, route.weight());
        }
        long pick = ThreadLocalRandom.current().nextLong(total);
        for (Route route : candidates) {
            pick -= Math.max(1, route.weight());
            if (pick < 0) {
                return route;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    public List<Route> fallbacks(Route primary) {
        if (primary == null) {
            return List.of();
        }
        List<Route> result = new ArrayList<>();
        for (String id : primary.fallbackIds()) {
            Route route = byId.get(id);
            if (route != null && !route.id().equals(primary.id())) {
                result.add(route);
            }
        }
        return result;
    }

    public List<Route> routes() { return routes; }
    public Route fallback() { return fallback; }
    public long version() { return version; }
}
