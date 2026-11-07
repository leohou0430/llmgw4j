package io.github.leohou0430.llmgw.engine;

/**
 * Compile-time constants for {@code @Profile} annotations. Kept separate from
 * {@link EngineKind} because enum constants cannot forward-reference fields
 * declared later in the same enum (JLS 8.3.3).
 */
public final class EngineProfiles {

    public static final String WEBFLUX = "webflux";
    public static final String VT = "vt";

    private EngineProfiles() {
    }
}
