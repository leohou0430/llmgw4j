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
            registerNamedExtensions(hints, classLoader);
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
        private static void registerNamedExtensions(RuntimeHints hints, ClassLoader requestedLoader) {
            ClassLoader loader = requestedLoader == null ? GatewayRuntimeHints.class.getClassLoader() : requestedLoader;
            hints.resources().registerPattern("META-INF/llmgw/extensions/**");
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(loader);
            try {
                for (var resource : resources.getResources("classpath*:META-INF/llmgw/extensions/**")) {
                    if (!resource.isReadable() || resource.getURL().toExternalForm().endsWith("/")) continue;
                    registerAvailable(hints, loader, resource.getFilename());
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        for (String line; (line = reader.readLine()) != null;) {
                            line = line.split("#", 2)[0].trim();
                            if (line.isEmpty()) continue;
                            int separator = line.indexOf('=');
                            String name = (separator < 0 ? line : line.substring(separator + 1)).trim();
                            registerAvailable(hints, loader, name);
                        }
                    }
                }
            } catch (java.io.IOException error) { throw new IllegalStateException("cannot read named extension metadata for AOT", error); }
        }

        private static void registerAvailable(RuntimeHints hints, ClassLoader loader, String name) {
            if (name == null || !name.contains(".")) return;
            try {
                Class<?> type = Class.forName(name, false, loader);
                hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            } catch (ClassNotFoundException | LinkageError optional) {
                throw new IllegalStateException("required extension missing during AOT: " + name, optional);
            }
        }

    }
}
