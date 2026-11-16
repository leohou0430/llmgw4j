package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.*;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable, optimistic route publication. Activation follows durable commit. */
public final class RouteVersionService {
    private final RouteVersionStore store;
    private final java.util.function.Function<String, Consumer<RouteVersion>> preparer;
    private final ThreadLocal<String> legacyDraft = new ThreadLocal<>();
    private volatile RouteVersion active;

    public RouteVersionService(RouteVersionStore store) { this(store, config -> {}, version -> {}); }
    public RouteVersionService(RouteVersionStore store, Consumer<String> validator, Consumer<RouteVersion> activator) {
        this(store, config -> { validator.accept(config); return activator; });
    }
    public RouteVersionService(RouteVersionStore store, java.util.function.Function<String,Consumer<RouteVersion>> preparer) {
        this.store=Objects.requireNonNull(store);this.preparer=Objects.requireNonNull(preparer);
        store.latest().ifPresent(version -> activate(version,preparer.apply(version.config())));
    }
    /** Compatibility only. HTTP callers must publish the candidate in one call. */
    @Deprecated public void saveDraft(String config) {
        requireConfig(config);legacyDraft.set(config);
    }
    @Deprecated public RouteVersion publish(long expectedVersion) {
        String candidate=legacyDraft.get();legacyDraft.remove();
        if(candidate==null)throw new IllegalStateException("no draft to publish");
        return publish(candidate,expectedVersion);
    }
    public RouteVersion publish(String config,long expectedVersion) {
        requireConfig(config);
        return append(config,0,expectedVersion,preparer.apply(config));
    }
    public RouteVersion rollback(long targetVersion,long expectedVersion) {
        RouteVersion target=store.find(targetVersion).orElseThrow(()->new IllegalArgumentException("unknown version "+targetVersion));
        return append(target.config(),targetVersion,expectedVersion,preparer.apply(target.config()));
    }
    private RouteVersion append(String config,long base,long expected,Consumer<RouteVersion> activation) {
        long latest=store.latest().map(RouteVersion::version).orElse(0L);
        if(expected!=latest)throw new VersionConflictException("expected version "+expected+" but latest is "+latest);
        RouteVersion version=new RouteVersion(Math.addExact(latest,1),config,base);
        store.append(version);
        store.afterCommit(()->activate(version,activation));
        return version;
    }
    private synchronized void activate(RouteVersion version,Consumer<RouteVersion> activation) { if(active!=null && active.version()>=version.version())return;activation.accept(version);active=version; }
    private static void requireConfig(String config) {if(config==null || config.isBlank())throw new IllegalArgumentException("route config is required");}
    public long activeVersion() {RouteVersion value=active;return value==null?0:value.version();}
    public String activeConfig() {RouteVersion value=active;return value==null?null:value.config();}
}
