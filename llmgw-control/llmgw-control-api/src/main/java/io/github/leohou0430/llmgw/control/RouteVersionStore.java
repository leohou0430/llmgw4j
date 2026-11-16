package io.github.leohou0430.llmgw.control;

import java.util.List;
import java.util.Optional;

/** Persists published route versions (append-only; history is never rewritten). */
public interface RouteVersionStore {

    Optional<RouteVersion> latest();

    Optional<RouteVersion> find(long version);

    List<RouteVersion> all();

    void append(RouteVersion version);
}