package io.github.leohou0430.llmgw.core.upstream;

/** Thrown locally (no network I/O) when a route's circuit is open. */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String routeId) {
        super("circuit open for route " + routeId);
    }
}
