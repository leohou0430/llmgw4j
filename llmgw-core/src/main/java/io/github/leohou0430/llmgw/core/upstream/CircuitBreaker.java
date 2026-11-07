package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.routing.Route;

/**
 * Per-route upstream health gate (circuit breaker SPI).
 *
 * <p>Policy contract implemented by providers:
 * <ul>
 *   <li>{@code failure} = an attempt that threw before delivering ANY chunk
 *       (connect refused, non-2xx, TLS error). A stream that already delivered
 *       data is connectivity-healthy regardless of how it later ends.</li>
 *   <li>{@code success} = the attempt delivered at least one chunk or completed
 *       normally.</li>
 *   <li>Client cancellation records neither: the upstream may be perfectly
 *       healthy, the client just went away.</li>
 * </ul>
 *
 * <p>Engines call {@link #allow} before each attempt; an open route fails fast
 * with {@link CircuitOpenException} (locally, no network I/O) so the fallback
 * chain proceeds to the next candidate.
 */
public interface CircuitBreaker {

    /** false = the route is open; skip it and try the next candidate. */
    boolean allow(Route route);

    void recordSuccess(Route route);

    void recordFailure(Route route);

    /** Always-allow implementation; used when breaking is disabled. */
    static CircuitBreaker noop() {
        return new CircuitBreaker() {
            @Override public boolean allow(Route route) { return true; }
            @Override public void recordSuccess(Route route) { }
            @Override public void recordFailure(Route route) { }
        };
    }
}
