package io.github.leohou0430.llmgw.core.extension;

import java.util.*;
import java.util.function.Supplier;

/** Per-runtime explicit definitions and ownership; no scanning, reflection-based injection or Spring. */
public final class ExtensionContext implements AutoCloseable {
    private final Extensions<ExtensionModule> extensions = Extensions.load(ExtensionModule.class);
    private final Map<Class<?>, Supplier<?>> definitions = new LinkedHashMap<>();
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Set<Class<?>> external = new HashSet<>();
    private final Set<Class<?>> constructing = new HashSet<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final Set<Object> tracked = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean sealed;
    private boolean closed;

    public synchronized <T> void bind(Class<T> type, Supplier<? extends T> factory) {
        configuring();
        Objects.requireNonNull(type);Objects.requireNonNull(factory);
        if (definitions.putIfAbsent(type, factory) != null) throw new IllegalStateException("duplicate definition: " + type.getName());
    }

    public synchronized <T> void bindDefault(Class<T> type, Supplier<? extends T> factory) {
        configuring();
        if (!definitions.containsKey(type)) bind(type, factory);
    }

    public synchronized <T> void replace(Class<T> type, Supplier<? extends T> factory) {
        configuring();
        if (!definitions.containsKey(type)) throw new IllegalStateException("cannot replace missing definition: " + type.getName());
        if (instances.containsKey(type)) throw new IllegalStateException("cannot replace an instantiated service: " + type.getName());
        definitions.put(type, Objects.requireNonNull(factory));
        external.remove(type);
    }

    public synchronized <T> void external(Class<T> type, T instance) {
        Objects.requireNonNull(instance);
        bind(type, () -> instance);
        external.add(type);
    }

    public synchronized boolean contains(Class<?> type) { open();return definitions.containsKey(type); }

    public synchronized <T> T get(Class<T> type) {
        open();
        Object cached = instances.get(type);
        if (cached != null) return type.cast(cached);
        Supplier<?> factory = definitions.get(type);
        if (factory == null) throw new IllegalStateException("missing definition: " + type.getName());
        if (!constructing.add(type)) throw new IllegalStateException("circular definition: " + type.getName());
        try {
            T value = type.cast(Objects.requireNonNull(factory.get(), "factory returned null: " + type.getName()));
            instances.put(type, value);
            if (!external.contains(type) && value instanceof AutoCloseable resource && tracked.add(value)) owned.add(resource);
            return value;
        } finally {
            constructing.remove(type);
        }
    }

    public synchronized void loadModule(String name) {
        configuring();
        extensions.getExtension(name).configure(this);
    }

    public synchronized void seal() { open();sealed = true; }

    private void configuring() {
        open();
        if (sealed) throw new IllegalStateException("runtime definitions are sealed");
    }
    private void open() { if (closed) throw new IllegalStateException("runtime is closed"); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (int i = owned.size() - 1; i >= 0; i--) {
            try { owned.get(i).close(); }
            catch (Exception e) {
                if (failure == null) failure = new IllegalStateException("failed to close runtime resources");
                failure.addSuppressed(e);
            }
        }
        definitions.clear();instances.clear();owned.clear();tracked.clear();
        if (failure != null) throw failure;
    }
}
