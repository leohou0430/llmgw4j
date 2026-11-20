package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.*;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ControlScopeIsolationTest {

	@TempDir
	Path directory;

	@Test
	void projectListsAndTargetsFollowRealScopeAndAuditFailureRollsBack() throws Exception {
		try (var database = new SqliteContext(directory.resolve("control.db"), 1000)) {
			new SchemaMigrator(database).migrate(Migrations.all());
			var orgs = new OrgStore(database);
			orgs.createTenant("t1", "one");
			orgs.createTenant("t2", "two");
			orgs.createProject("p1", "t1", "one");
			orgs.createProject("p2", "t2", "two");
			var accounts = new SqliteAccountStore(database);
			accounts.create(new Account("a", "viewer", "hash", Role.PROJECT_VIEWER, Scope.project("t2", "p2"), true));
			var sessions = new SqliteSessionStore(database);
			var sid = sessions.create("a", 60_000, System.currentTimeMillis()).id();
			var csrf = HmacCsrfTokenService.randomKey();
			var auth = new RequestAuthorizer(sessions, csrf, accounts, new RbacAuthorizationService(),
					System::currentTimeMillis);
			var controller = new ControlOrgController(auth, orgs, new AuditLog(database),
					new StorageGuard(Long.MAX_VALUE, 0, directory.resolve("control.db")));
			assertEquals(java.util.List.of("t2"),
					((java.util.List<OrgStore.Tenant>) controller.tenants(sid).getBody().get("tenants")).stream()
						.map(OrgStore.Tenant::id)
						.toList());
			assertEquals(java.util.List.of("p2"),
					((java.util.List<OrgStore.Project>) controller.projects(sid).getBody().get("projects")).stream()
						.map(OrgStore.Project::id)
						.toList());
			assertThrows(ResponseStatusException.class, () -> ControlAccess.resolve(auth, orgs, sid, "t2", "p1"));
			var login = new ControlLoginController(accounts,
					new SessionService(sessions, new Pbkdf2PasswordHasher(1), 60_000), csrf, sessions, auth,
					new io.github.leohou0430.llmgw.core.config.GatewayProperties());
			var restored = login.restore(sid);
			assertNotNull(restored.getBody().get("csrfToken"));
			assertEquals("no-store", restored.getHeaders().getFirst("Cache-Control"));
			login.logout(sid, (String) restored.getBody().get("csrfToken"));
			assertTrue(sessions.find(sid, System.currentTimeMillis()).isEmpty());
			database.transaction(connection -> {
				try (var statement = connection.createStatement()) {
					statement.execute(
							"CREATE TRIGGER fail_audit BEFORE INSERT ON audit_event BEGIN SELECT RAISE(ABORT, 'audit fault'); END");
				}
				return null;
			});
			assertThrows(IllegalStateException.class,
					() -> new AuditLog(database).change("a", "t1", "", "tenant.create", "test", () -> {
						orgs.createTenant("rollback", "rollback");
						return null;
					}));
			assertTrue(orgs.tenants("rollback").isEmpty());
		}
	}

}
