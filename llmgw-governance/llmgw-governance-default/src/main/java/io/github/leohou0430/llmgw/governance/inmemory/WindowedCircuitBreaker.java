package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal lock-free per-route circuit breaker.
 *
 * <ul>
 *   <li><b>CLOSED</b>: requests flow. Consecutive no-delivery failures are
 *       counted; reaching {@code failure-threshold} opens the circuit.</li>
 *   <li><b>OPEN</b>: {@link #allow} returns false immediately (the engine then
 *       tries the next fallback candidate without any network I/O). After
 *       {@code cooldown-ms} the circuit admits exactly ONE half-open probe
 *       (CAS on a boolean, so concurrent callers do not stampede the upstream).</li>
 *   <li>Probe success closes the circuit and clears the counter; probe failure
 *       restarts the cooldown.</li>
 * </ul>
 *
 * <p>Threshold &lt;= 0 disables breaking: {@link #allow} always returns true.
 * State lives in a bounded map keyed by route id (routes are static config),
 * so there is no unbounded growth and nothing to evict.
 */
public class WindowedCircuitBreaker implements CircuitBreaker {

    private final int threshold;
    private final long cooldownMs;
    private final boolean circuitEnabled;
    private final GovernanceMetrics metrics;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final AtomicInteger failures = new AtomicInteger();
        /** 0 = closed; nonzero = epoch millis when the circuit opened. */
        volatile long openedAtMs;
        /** Half-open probe slot: only one in-flight probe while open. */
        final AtomicBoolean probing = new AtomicBoolean(false);
    }

    public WindowedCircuitBreaker(GatewayProperties properties, GovernanceMetrics metrics) {
        GovernanceLimits limits = GovernanceLimits.from(properties);
        this.threshold = limits.circuitFailureThreshold();
        this.cooldownMs = limits.circuitCooldownMs();
        this.circuitEnabled = limits.circuitEnabled();
        this.metrics = metrics;
    }

    @Override
    public boolean allow(Route route) {
        if (!circuitEnabled) {
            return true;
        }
        State state = states.get(route.id());
        if (state == null || state.openedAtMs == 0) {
            return true;
        }
        if (System.currentTimeMillis() - state.openedAtMs >= cooldownMs) {
            // Half-open: admit exactly one probe; everyone else still sees OPEN.
            return state.probing.compareAndSet(false, true);
        }
        return false;
    }

    @Override
    public void recordSuccess(Route route) {
        State state = states.get(route.id());
        if (state == null) {
            return;
        }
        if (state.openedAtMs != 0) {
            metrics.count("llmgw.circuit", "event", "closed", "route", route.id());
        }
        state.failures.set(0);
        state.openedAtMs = 0;
        state.probing.set(false);
    }

    @Override
    public void recordFailure(Route route) {
        if (!circuitEnabled) {
            return;
        }
        State state = states.computeIfAbsent(route.id(), k -> new State());
        int failures = state.failures.incrementAndGet();
        boolean halfOpenProbe = state.openedAtMs != 0;
        if (failures >= threshold || halfOpenProbe) {
            state.openedAtMs = System.currentTimeMillis();
            state.probing.set(false);
            metrics.count("llmgw.circuit", "event", halfOpenProbe ? "reopened" : "open",
                    "route", route.id());
        }
    }

    /** Introspection for the admin surface: route id -> failures / open state. */
    public Map<String, Object> view() {
        Map<String, Object> out = new ConcurrentHashMap<>();
        states.forEach((id, state) -> {
            boolean open = state.openedAtMs != 0
                    && System.currentTimeMillis() - state.openedAtMs < cooldownMs;
            out.put(id, Map.of(
                    "open", open,
                    "consecutiveFailures", state.failures.get(),
                    "halfOpenProbeInFlight", state.probing.get()));
        });
        return out;
    }
}
