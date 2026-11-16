package io.github.leohou0430.llmgw.control;

import java.util.Objects;

/**
 * An immutable published route configuration version. History is never rewritten;
 * {@code baseVersion} records the version a rollback references (0 for a normal publish).
 */
public record RouteVersion(long version, String config, long baseVersion) {

    public RouteVersion {
        Objects.requireNonNull(config, "config");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}