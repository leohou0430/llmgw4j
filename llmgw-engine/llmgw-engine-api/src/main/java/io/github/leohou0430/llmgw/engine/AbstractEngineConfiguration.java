package io.github.leohou0430.llmgw.engine;

import java.util.Objects;

/**
 * Base for the per-engine {@code @Configuration} beans. Each concrete subclass
 * keeps its own {@code @Profile} and package (the composition root scans only
 * the implementation packages), while engine identity registration is defined
 * once here.
 *
 * <p>This class deliberately carries no Spring annotation: it is a plain base
 * class, not a component, so component scanning the implementation packages
 * never registers a second, profile-less bean.
 */
public abstract class AbstractEngineConfiguration {

    private final EngineKind kind;

    protected AbstractEngineConfiguration(EngineKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.kind.activate();
    }

    public final EngineKind kind() {
        return kind;
    }
}
