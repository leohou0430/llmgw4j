package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface-layer contract of the shared attempt-ordering module. The two data
 * planes trust this policy for WHICH attempt runs next and WHEN to stop; these
 * tests pin the ordering, the retry classification and the backoff bounds so a
 * reactive-vs-blocking divergence can never hide here.
 */
class ForwardingPolicyTest {

    private static Route route(String id) {
        return new Route(id, ProtocolKind.CHAT.wireName(), "*", "", "http://u/" + id, "", 1, List.of());
    }

    /** Router stub: fallbacks are resolved from the primary's fallbackIds. */
    private static Router routerOf(List<Route> routes) {
        return new Router() {
            @Override public Route resolve(String model) { return routes.get(0); }
            @Override public Route resolve(ProtocolKind kind, String model) { return routes.get(0); }
            @Override public Route byId(String id) {
                return routes.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
            }
            @Override public List<Route> fallbacks(Route primary) {
                return primary.fallbackIds().stream()
                        .map(id -> routes.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow())
                        .toList();
            }
            @Override public List<Route> all() { return routes; }
        };
    }

    @Test
    void planWithRetriesOffIsPrimaryThenFallbacksWithNoDelays() {
        Router router = routerOf(List.of(route("a"), route("b"), route("c")));
        Route primary = new Route("a", ProtocolKind.CHAT.wireName(), "*", "", "http://u", "", 1,
                List.of("b", "c"));
        List<ForwardingPolicy.Attempt> plan = ForwardingPolicy.plan(router, primary,
                ForwardingPolicy.RetryConfig.off());
        assertEquals(List.of("a", "b", "c"),
                plan.stream().map(a -> a.route().id()).collect(Collectors.toList()));
        assertTrue(plan.stream().allMatch(a -> a.delayBeforeMs() == 0));
        assertTrue(plan.stream().allMatch(a -> a.ordinal() == 1));
    }

    @Test
    void planWithRetriesInterleavesSameRouteAttemptsBeforeFallback() {
        Router router = routerOf(List.of(route("a"), route("b")));
        Route primary = new Route("a", ProtocolKind.CHAT.wireName(), "*", "", "http://u", "", 1,
                List.of("b"));
        List<ForwardingPolicy.Attempt> plan = ForwardingPolicy.plan(router, primary,
                new ForwardingPolicy.RetryConfig(3, 100, 800));
        assertEquals(List.of("a", "a", "a", "b", "b", "b"),
                plan.stream().map(x -> x.route().id()).collect(Collectors.toList()));
        assertEquals(List.of(1, 2, 3, 1, 2, 3),
                plan.stream().map(ForwardingPolicy.Attempt::ordinal).collect(Collectors.toList()));
        // First attempt of every route pays no delay; retries do.
        assertTrue(plan.get(0).delayBeforeMs() == 0);
        assertTrue(plan.get(3).delayBeforeMs() == 0);
        assertTrue(plan.get(1).delayBeforeMs() > 0 && plan.get(2).delayBeforeMs() > 0);
        assertTrue(plan.get(4).delayBeforeMs() > 0 && plan.get(5).delayBeforeMs() > 0);
    }

    @Test
    void backoffStaysWithinJitterBounds() {
        ForwardingPolicy.RetryConfig retry = new ForwardingPolicy.RetryConfig(6, 100, 1000);
        for (int run = 0; run < 50; run++) {
            long d1 = ForwardingPolicy.backoffDelayMs(retry, 2);
            // ceiling = 100 * 2^1 = 200, jitter draws from [100, 200)
            assertTrue(d1 >= 100 && d1 <= 200, "ordinal2=" + d1);
            long d3 = ForwardingPolicy.backoffDelayMs(retry, 4);
            // ceiling = min(1000, 100 * 2^3) = 800, jitter draws from [400, 800)
            assertTrue(d3 >= 400 && d3 <= 800, "ordinal4=" + d3);
        }
    }

    @Test
    void nullPrimaryYieldsEmptyPlanAndNextViableReportsExhausted() {
        List<ForwardingPolicy.Attempt> plan = ForwardingPolicy.plan(routerOf(List.of()), null,
                ForwardingPolicy.RetryConfig.off());
        assertTrue(plan.isEmpty());
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 0, new RuntimeException("x")));
    }

    @Test
    void retryableClassificationFollowsHttpSemantics() {
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(500, "boom")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(503, "boom")));
        assertTrue(ForwardingPolicy.retryable(new UpstreamStatusException(429, "rate limited")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(408, "timeout")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(400, "bad request")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(401, "unauthorized")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(403, "forbidden")));
        assertFalse(ForwardingPolicy.retryable(new UpstreamStatusException(413, "too large")));
        // An open circuit must not burn another delay cycle on the same route.
        assertFalse(ForwardingPolicy.retryable(new CircuitOpenException("a")));
        // Transport/timeouts/truncation-style IllegalStateExceptions are transient.
        assertFalse(ForwardingPolicy.retryable(new IllegalStateException("connection reset")));
    }

    @Test
    void nextViableRetriesSameRouteOnRetryableError() {
        List<ForwardingPolicy.Attempt> plan = planOf(2, List.of("a", "b"));
        assertEquals(1, ForwardingPolicy.nextViable(plan, 0, new UpstreamStatusException(429, "x")));
    }

    @Test
    void nextViableSkipsRemainingSameRouteAttemptsOnNonRetryableError() {
        List<ForwardingPolicy.Attempt> plan = planOf(3, List.of("a", "b"));
        // a1 a2 a3 b1: a 400 skips a2/a3 and lands on b1 (index 3).
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 0, new UpstreamStatusException(400, "x")));
    }

    @Test
    void nextViableReportsExhaustedChain() {
        List<ForwardingPolicy.Attempt> plan = planOf(2, List.of("a"));
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 1, new UpstreamStatusException(429, "x")));
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 0, new UpstreamStatusException(400, "x")));
    }

    @Test
    void unknownOutcomeStopsAllAttemptsButUnsentConnectionsMayFailOver() {
        var plan = planOf(2, List.of("a", "b"));
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 0,
                new IllegalStateException(new java.net.http.HttpTimeoutException("unknown outcome"))));
        assertEquals(-1, ForwardingPolicy.nextViable(plan, 0, new UpstreamStatusException(500, "unknown")));
        assertEquals(1, ForwardingPolicy.nextViable(plan, 0,
                new IllegalStateException(new java.net.ConnectException("refused"))));
        assertEquals(2, ForwardingPolicy.nextViable(plan, 0, new CircuitOpenException("a")));
    }

    private static List<ForwardingPolicy.Attempt> planOf(int attempts, List<String> routeIds) {
        java.util.ArrayList<ForwardingPolicy.Attempt> plan = new java.util.ArrayList<>();
        for (String id : routeIds) {
            for (int ordinal = 1; ordinal <= attempts; ordinal++) {
                plan.add(new ForwardingPolicy.Attempt(route(id), ordinal, ordinal == 1 ? 0 : 10));
            }
        }
        return plan;
    }
}
