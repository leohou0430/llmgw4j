package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(GatewayRuntimeHints.Registrar.class)
public class GatewayRuntimeHints {

    static class Registrar implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            MemberCategory[] all = MemberCategory.values();
            registerProperties(hints, GatewayProperties.class, all);
            registerDubboExtensions(hints, classLoader, all);
            hints.reflection().registerType(ParsedRequest.class, all);
            hints.reflection().registerType(Route.class, all);
            hints.reflection().registerType(GatewayDecision.class, all);
            hints.reflection().registerType(UsageLedger.UsageSummary.class, all);
            // Every record reachable from the /admin/* JSON responses must be
            // registered, otherwise Jackson 3 calls Class.getRecordComponents()
            // and native image throws UnsupportedFeatureError -> HTTP 500.
            // Found by running the error-contract suite against the AOT binary:
            // /admin/snapshot failed on AuditSink.AuditEvent.
            hints.reflection().registerType(AuditSink.AuditEvent.class, all);
            hints.reflection().registerType(ApiKeyRegistry.ApiKey.class, all);
            // POST /admin/routes/reload binds this record; without a hint the
            // AOT image cannot call Class.getRecordComponents() on it.
            hints.reflection().registerType(AdminController.RouteSpec.class, all);
            hints.resources().registerPattern("application*.yml");
            hints.resources().registerPattern("application*.yaml");
        }
        private static void registerProperties(RuntimeHints hints, Class<?> type, MemberCategory[] all) {
            hints.reflection().registerType(type, all);
            for (Class<?> nested : type.getDeclaredClasses()) registerProperties(hints, nested, all);
        }

        /** Discover metadata at AOT time; runtime extension loading does no Spring scanning. */
        private static void registerDubboExtensions(RuntimeHints hints, ClassLoader requestedLoader, MemberCategory[] all) {
            ClassLoader loader = requestedLoader == null ? GatewayRuntimeHints.class.getClassLoader() : requestedLoader;
            hints.resources().registerPattern("META-INF/dubbo/**");
            hints.resources().registerPattern("META-INF/services/org.apache.dubbo.*");
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(loader);
            try {
                for (var resource : resources.getResources("classpath*:META-INF/dubbo/**")) {
                    if (!resource.isReadable() || resource.getURL().toExternalForm().endsWith("/")) continue;
                    registerAvailable(hints, loader, resource.getFilename(), all);
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        for (String line; (line = reader.readLine()) != null;) {
                            line = line.split("#", 2)[0].trim();
                            if (line.isEmpty()) continue;
                            int separator = line.indexOf('=');
                            String name = (separator < 0 ? line : line.substring(separator + 1)).trim();
                            registerAvailable(hints, loader, name, all);
                        }
                    }
                }
            } catch (java.io.IOException error) { throw new IllegalStateException("cannot read Dubbo extension metadata for AOT", error); }
        }

        private static void registerAvailable(RuntimeHints hints, ClassLoader loader, String name, MemberCategory[] all) {
            if (name == null || !name.contains(".")) return;
            try {
                Class<?> type = Class.forName(name, false, loader);
                // Optional Dubbo adapters can reference logging libraries absent from this artifact.
                type.getDeclaredConstructors();type.getDeclaredMethods();type.getDeclaredFields();
                if (name.startsWith("io.github.leohou0430.llmgw.")) {
                    hints.reflection().registerType(type, all);
                } else {
                    // Dubbo discovers constructors and inspects setters. Registering every
                    // third-party method for invocation roots optional JSON/compiler paths.
                    hints.reflection().registerType(type,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INTROSPECT_PUBLIC_METHODS);
                    for (var method : type.getMethods()) {
                        if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
                            hints.reflection().registerMethod(method, org.springframework.aot.hint.ExecutableMode.INVOKE);
                        }
                    }
                }
            } catch (ClassNotFoundException | LinkageError optional) {
                if (name.startsWith("io.github.leohou0430.llmgw.")) throw new IllegalStateException("required extension missing during AOT: " + name, optional);
            }
        }

    }
}
