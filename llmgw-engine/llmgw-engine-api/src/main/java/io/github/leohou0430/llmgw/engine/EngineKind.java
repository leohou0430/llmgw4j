package io.github.leohou0430.llmgw.engine;

import java.util.Optional;

/**
 * Identity of a data-plane implementation.
 *
 * <p>Exactly one engine is active per JVM, selected by a Spring profile. The
 * active id is published as a system property because the governance layer
 * (which lives below the engine and must not depend on it) reads it for metric
 * labels. This enum is the single source of truth for that string contract.
 */
public enum EngineKind {

    WEBFLUX(EngineProfiles.WEBFLUX),
    VT(EngineProfiles.VT);

    /** Property read by {@code DefaultGovernanceChain} for the engine metric tag. */
    public static final String SYSTEM_PROPERTY = "llmgw.engine";

    private final String profile;

    EngineKind(String profile) {
        this.profile = profile;
    }

    /** Spring profile that activates this engine; also the metric label value. */
    public String profile() {
        return profile;
    }

    /** Reverse lookup used by the early environment post-processor. */
    public static Optional<EngineKind> fromProfile(String profile) {
        for (EngineKind kind : values()) {
            if (kind.profile.equals(profile)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * Publish this engine as the active data plane. Called during environment
     * preparation (see the app module's {@code EngineKindEnvironmentPostProcessor})
     * and again, idempotently, by the engine's configuration bean.
     */
    public void activate() {
        System.setProperty(SYSTEM_PROPERTY, profile);
    }
}
