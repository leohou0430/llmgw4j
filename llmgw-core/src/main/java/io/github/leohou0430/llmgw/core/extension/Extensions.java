package io.github.leohou0430.llmgw.core.extension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Named, lazy discovery using only JDK APIs; each catalog owns its cache/class loader. */
public final class Extensions<T> {
    public static final String DIRECTORY = "META-INF/llmgw/extensions/";
    private final Class<T> type;
    private final ClassLoader classLoader;
    private final String defaultName;
    private final Map<String, String> implementations;
    private final Map<String, T> instances = new HashMap<>();

    public static <T> Extensions<T> load(Class<T> type) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return load(type, loader == null ? type.getClassLoader() : loader);
    }

    public static <T> Extensions<T> load(Class<T> type, ClassLoader loader) {
        return new Extensions<>(type, loader);
    }

    private Extensions(Class<T> type, ClassLoader loader) {
        this.type = Objects.requireNonNull(type);
        this.classLoader = Objects.requireNonNull(loader);
        ExtensionPoint point = type.getAnnotation(ExtensionPoint.class);
        if (!type.isInterface() || point == null) {
            throw new IllegalArgumentException("extension contract must be an @ExtensionPoint interface: " + type.getName());
        }
        this.defaultName = point.value().trim();
        if (!defaultName.isEmpty() && !validName(defaultName)) {
            throw new IllegalStateException("invalid default extension name for " + type.getName());
        }
        this.implementations = discover();
    }

    public Set<String> getSupportedExtensions() { return implementations.keySet(); }
    public boolean hasExtension(String name) { return name != null && implementations.containsKey(name); }

    public T getDefaultExtension() {
        if (defaultName.isEmpty()) throw new IllegalStateException("no default extension for " + type.getName());
        return getExtension(defaultName);
    }

    public synchronized T getExtension(String name) {
        String implementation = name == null ? null : implementations.get(name);
        if (implementation == null) {
            throw new IllegalStateException("unknown extension '" + name + "' for " + type.getName()
                    + "; available: " + implementations.keySet());
        }
        T cached = instances.get(implementation);
        if (cached != null) return cached;
        try {
            Class<?> candidate = Class.forName(implementation, false, classLoader);
            if (!type.isAssignableFrom(candidate)) {
                throw new IllegalStateException(implementation + " does not implement " + type.getName());
            }
            T instance = type.cast(candidate.getConstructor().newInstance());
            instances.put(implementation, instance);
            return instance;
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("cannot construct extension " + implementation + " for " + type.getName(), error.getCause());
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("cannot load extension " + implementation + " for " + type.getName(), error);
        }
    }

    private Map<String, String> discover() {
        Map<String, String> mappings = new TreeMap<>();
        Set<String> visited = new HashSet<>();
        try {
            var resources = classLoader.getResources(DIRECTORY + type.getName());
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                if (!visited.add(resource.toExternalForm())) continue;
                try (var reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
                    int number = 0;
                    for (String line; (line = reader.readLine()) != null;) {
                        number++;
                        line = line.split("#", 2)[0].trim();
                        if (line.isEmpty()) continue;
                        int separator = line.indexOf('=');
                        if (separator <= 0 || separator == line.length() - 1) {
                            throw new IllegalStateException("invalid extension mapping at " + resource + ":" + number);
                        }
                        String implementation = line.substring(separator + 1).trim();
                        if (!implementation.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*")) {
                            throw new IllegalStateException("invalid extension class at " + resource + ":" + number);
                        }
                        for (String alias : line.substring(0, separator).split(",", -1)) {
                            alias = alias.trim();
                            if (!validName(alias)) throw new IllegalStateException("invalid extension alias at " + resource + ":" + number);
                            if (mappings.putIfAbsent(alias, implementation) != null) {
                                throw new IllegalStateException("duplicate extension '" + alias + "' for " + type.getName()
                                        + " at " + resource + ":" + number);
                            }
                        }
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot read extension metadata for " + type.getName(), error);
        }
        return java.util.Collections.unmodifiableMap(mappings);
    }

    private static boolean validName(String name) { return name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"); }
}
