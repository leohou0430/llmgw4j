package io.github.leohou0430.llmgw.control;

import java.util.Objects;
import java.util.Set;

/**
 * Platform-level policy for upstream targets. Only the platform may widen it; a project
 * configuration can never relax it (so a project cannot enable a disallowed scheme or a
 * private/loopback/metadata address).
 */
public record PlatformPolicy(Set<String> allowedSchemes, boolean allowPrivateNetwork) {

    public PlatformPolicy {
        allowedSchemes = allowedSchemes == null ? Set.of() : Set.copyOf(allowedSchemes);
    }

    public static PlatformPolicy strict() {
        return new PlatformPolicy(Set.of("https"), false);
    }

    public boolean schemeAllowed(String scheme) {
        return scheme != null && allowedSchemes.contains(scheme.toLowerCase());
    }

    public static PlatformPolicy of(Set<String> allowedSchemes, boolean allowPrivateNetwork) {
        return new PlatformPolicy(Objects.requireNonNull(allowedSchemes, "allowedSchemes"), allowPrivateNetwork);
    }
}