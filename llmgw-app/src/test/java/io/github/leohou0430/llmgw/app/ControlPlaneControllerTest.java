package io.github.leohou0430.llmgw.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4.4/T4.10 wiring seam: the control-plane foundation is wired into the app and reachable
 * through the /control/status surface, reporting a safe invariant (credentials never
 * readable, even by platform admin).
 */
class ControlPlaneControllerTest {

    @Test
    void statusReportsWiredControlPlaneAndSafetyInvariant() {
        Map<String, Object> status = new ControlPlaneController().status();

        assertEquals("RbacAuthorizationService", status.get("rbac"));
        assertEquals("CredentialIssuer", status.get("keyLifecycle"));
        assertEquals("RouteVersionService", status.get("routeVersions"));
        assertEquals("DefaultConfigValidator", status.get("configValidation"));
        assertEquals(0L, status.get("activeRouteVersion"));
        assertEquals(Boolean.TRUE, status.get("credentialReadDenied"));
    }
}