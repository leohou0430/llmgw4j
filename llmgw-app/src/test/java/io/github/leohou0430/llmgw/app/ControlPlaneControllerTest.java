package io.github.leohou0430.llmgw.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4.4/T4.10 wiring seam: the control-plane foundation is wired into the app and
 * reachable through the /control/status surface, reporting a safe invariant (credentials
 * never readable, even by platform admin).
 */
class ControlPlaneControllerTest {

	@Test
	void statusReportsWiredControlPlaneAndSafetyInvariant() throws java.sql.SQLException {
		var identity = new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService();
		var sessions = new io.github.leohou0430.llmgw.identity.local.InMemorySessionStore();
		var accounts = new io.github.leohou0430.llmgw.identity.local.InMemoryAccountStore();
		var csrf = io.github.leohou0430.llmgw.identity.local.HmacCsrfTokenService.randomKey();
		var authorizer = new io.github.leohou0430.llmgw.identity.local.RequestAuthorizer(sessions, csrf, accounts,
				identity, System::currentTimeMillis);
		var audit = new AuditLog(new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(
				java.nio.file.Path.of(java.util.UUID.randomUUID() + ".db"), 1000));
		var payloads = new io.github.leohou0430.llmgw.persistence.sqlite.SqlitePayloadRecordStore(
				new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(
						java.nio.file.Path.of(java.util.UUID.randomUUID() + ".db"), 1000));
		Map<String, Object> status = new ControlPlaneController(
				new io.github.leohou0430.llmgw.control.local.RouteVersionService(
						new io.github.leohou0430.llmgw.control.local.InMemoryRouteVersionStore()),
				payloads, authorizer, audit)
			.status();

		assertEquals("RbacAuthorizationService", status.get("rbac"));
		assertEquals("CredentialIssuer", status.get("keyLifecycle"));
		assertEquals("RouteVersionService", status.get("routeVersions"));
		assertEquals("DefaultConfigValidator", status.get("configValidation"));
		assertEquals(0L, status.get("activeRouteVersion"));
		assertEquals(Boolean.TRUE, status.get("credentialReadDenied"));
	}

}
