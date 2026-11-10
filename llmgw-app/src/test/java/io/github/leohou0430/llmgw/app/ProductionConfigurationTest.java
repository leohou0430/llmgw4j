package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationTest {
    private GatewayProperties initialize(Map<String, Object> overrides) {
        var env = new StandardEnvironment();
        var values = new java.util.HashMap<String, Object>();
        values.put("gateway.production", "true");
        values.put("gateway.admin-token", "strong-admin-token");
        values.put("gateway.api-keys[0].id", "team");
        values.put("gateway.api-keys[0].token", "strong-client-token");
        values.putAll(overrides);
        env.getPropertySources().addFirst(new MapPropertySource("test", values));
        var p = Binder.get(env).bind("gateway", Bindable.of(GatewayProperties.class)).get();
        p.validate();
        return p;
    }
    @Test void productionRejectsMissingAdminToken() {
        assertThrows(Exception.class, () -> initialize(Map.of("gateway.admin-token", "")));
    }
    @Test void productionRejectsIdenticalFullAndReadOnlyAdminCredentials() {
        assertThrows(Exception.class, () -> initialize(Map.of("gateway.admin-read-token", "strong-admin-token")));
    }
    @Test void distinctAdminCredentialsRemainValid() {
        assertNotNull(initialize(Map.of("gateway.admin-read-token", "separate-read-token")));
    }
    @Test void productionRejectsDemoCredentials() {
        assertThrows(Exception.class, () -> initialize(Map.of("gateway.api-keys[0].token", "sk-demo")));
    }
    @Test void productionRejectsDisabledGovernance() {
        assertThrows(Exception.class, () -> initialize(Map.of("gateway.governance-enabled", "false")));
    }
    @Test void productionRejectsNonDurableMoneyBudget() {
        assertThrows(Exception.class, () -> initialize(Map.of("gateway.api-keys[0].budget-micro-usd", "100")));
    }
    @Test void safeProductionConfigurationStarts() { assertNotNull(initialize(Map.of())); }
    @Test void explicitDemoCanRemainOpen() {
        assertNotNull(initialize(Map.of("gateway.production", "false", "gateway.admin-token", "")));
    }
    @Test void audiencePropertyBindsAtTheApplicationEdge() {
        assertEquals("gateway", initialize(Map.of("gateway.auth.jwt-audience", "gateway")).getAuth().getJwtAudience());
    }
}
