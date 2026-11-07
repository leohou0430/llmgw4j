package io.github.leohou0430.llmgw.core.routing;

import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

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
        for (Route candidate : source) {
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()) {
                throw new IllegalArgumentException("route id must not be blank");
            }
            if (candidate.model() == null || candidate.model().isBlank()) {
                throw new IllegalArgumentException("route model must not be blank: " + candidate.id());
            }
            String protocol = candidate.protocol() == null ? "*" : candidate.protocol().trim();
            if (!ProtocolKind.isConfigValue(protocol)) {
                throw new IllegalArgumentException(
                        "unknown protocol '" + protocol + "' on route " + candidate.id()
                                + " (chat|responses|messages|images|videos|*)");
            }
            // Normalize BEFORE the byId insert so map and list hold the same object.
            Route route = candidate.protocol() != null && protocol.equals(candidate.protocol())
                    ? candidate
                    : new Route(candidate.id(), protocol.isBlank() ? "*" : protocol,
                            candidate.model(), candidate.targetModel(), candidate.targetUrl(),
                            candidate.apiKey(), candidate.weight(), candidate.fallbackIds());
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
        return resolve(ProtocolKind.CHAT, model);
    }

    /**
     * Protocol-aware resolution: a route matches when its protocol is "*" or
     * the requested kind, and its model is exact or "*". Specificity classes,
     * in order: exact protocol+model, exact protocol+wildcard model, then
     * protocol-agnostic routes (kept for pre-v12 single-route configs);
     * weight only orders within the same class.
     */
    public Route resolve(ProtocolKind kind, String model) {
        List<Route> exact = candidates(kind, model, false, false);
        if (!exact.isEmpty()) {
            return weightedPick(exact);
        }
        List<Route> protocolWildcard = candidates(kind, model, true, false);
        if (!protocolWildcard.isEmpty()) {
            return weightedPick(protocolWildcard);
        }
        List<Route> legacy = candidates(kind, model, true, true);
        return legacy.isEmpty() ? fallback : weightedPick(legacy);
    }

    /**
     * @param wildcardProtocol include routes whose protocol is "*" (skipped when
     *                         an exact-protocol route already matched)
     * @param wildcardModel    also include "*" model routes
     */
    private List<Route> candidates(ProtocolKind kind, String model,
                                   boolean wildcardProtocol, boolean wildcardModel) {
        List<Route> found = null;
        String wire = kind.wireName();
        for (Route route : routes) {
            boolean protocolMatch = route.matches(wire)
                    && (wildcardProtocol || !isWildcardProtocol(route));
            boolean modelMatch = wildcardModel
                    ? "*".equals(route.model()) || route.model().equals(model)
                    : route.model().equals(model);
            if (protocolMatch && modelMatch) {
                if (found == null) {
                    found = new ArrayList<>(2);
                }
                found.add(route);
            }
        }
        return found == null ? List.of() : found;
    }

    private static boolean isWildcardProtocol(Route route) {
        String protocol = route.protocol();
        return protocol == null || protocol.isBlank() || "*".equals(protocol);
    }

    /** Route lookup by id (video sticky routing); null when absent. */
    public Route byId(String id) {
        return id == null ? null : byId.get(id);
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
