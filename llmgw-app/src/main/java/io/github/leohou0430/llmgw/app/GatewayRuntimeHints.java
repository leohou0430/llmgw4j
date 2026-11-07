package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
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
    }
}
