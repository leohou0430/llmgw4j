package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.Decision;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4.3 seam: the RBAC matrix — platform admin global, tenant admin tenant-scoped (no
 * platform-only actions), project admin project-scoped, viewer read-only; credentials and
 * password hashes never readable; role grants cannot escalate peer/higher roles; any
 * out-of-scope target denied.
 */
class RbacAuthorizationServiceTest {

	private final AuthorizationService authz = new RbacAuthorizationService();

	private static Subject subject(Role role, Scope scope) {
		return new Subject(role, scope);
	}

	private static Target target(String tenant, String project) {
		return new Target(tenant, project);
	}

	private void assertAllowed(Subject subject, Action action, Target target) {
		assertEquals(Decision.ALLOW, authz.decide(subject, action, target),
				subject.role() + " " + action + " @" + target);
	}

	private void assertDenied(Subject subject, Action action, Target target) {
		assertEquals(Decision.DENY, authz.decide(subject, action, target),
				subject.role() + " " + action + " @" + target);
	}

	@Test
	void platformAdminActsGlobally() {
		Subject platform = subject(Role.PLATFORM_ADMIN, Scope.platform());
		assertAllowed(platform, Action.TENANT_MANAGE, target("t9", ""));
		assertAllowed(platform, Action.SOURCE_MANAGE, target("t9", "p9"));
		assertAllowed(platform, Action.KEY_MANAGE, target("t9", "p9"));
	}

	@Test
	void credentialsAndPasswordHashesAreNeverReadable() {
		for (Role role : Role.values()) {
			Scope scope = role == Role.PLATFORM_ADMIN ? Scope.platform()
					: role == Role.TENANT_ADMIN ? Scope.tenant("t1") : Scope.project("t1", "p1");
			Subject subject = subject(role, scope);
			assertDenied(subject, Action.READ_CREDENTIAL, target("t1", "p1"));
			assertDenied(subject, Action.READ_PASSWORD_HASH, target("t1", "p1"));
		}
	}

	@Test
	void tenantAdminIsTenantScopedAndCannotDoPlatformActions() {
		Subject tenant = subject(Role.TENANT_ADMIN, Scope.tenant("t1"));
		assertAllowed(tenant, Action.KEY_MANAGE, target("t1", "p1"));
		assertDenied(tenant, Action.KEY_MANAGE, target("t2", "p2"));
		assertDenied(tenant, Action.TENANT_MANAGE, target("t1", ""));
		assertDenied(tenant, Action.SOURCE_MANAGE, target("t1", "p1"));
	}

	@Test
	void projectAdminIsProjectScopedAndCannotManageGlobalSources() {
		Subject project = subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1"));
		assertAllowed(project, Action.KEY_MANAGE, target("t1", "p1"));
		assertAllowed(project, Action.CATALOG_WRITE, target("t1", "p1"));
		assertDenied(project, Action.KEY_MANAGE, target("t1", "p2"));
		assertDenied(project, Action.SOURCE_MANAGE, target("t1", "p1"));
	}

	@Test
	void viewerIsReadOnlyWithinProject() {
		Subject viewer = subject(Role.PROJECT_VIEWER, Scope.project("t1", "p1"));
		assertAllowed(viewer, Action.CATALOG_READ, target("t1", "p1"));
		assertAllowed(viewer, Action.AUDIT_READ, target("t1", "p1"));
		assertDenied(viewer, Action.KEY_MANAGE, target("t1", "p1"));
		assertDenied(viewer, Action.CATALOG_WRITE, target("t1", "p1"));
		assertDenied(viewer, Action.CATALOG_READ, target("t1", "p2"));
	}

	@Test
	void roleGrantsCannotEscalate() {
		Subject platform = subject(Role.PLATFORM_ADMIN, Scope.platform());
		assertAllowed(platform, Action.MEMBER_GRANT, target("t1", "p1")); // sanity:
																			// platform
																			// can manage
																			// members
		assertEquals(Decision.ALLOW, authz.grantRole(platform, target("t1", "p1"), Role.PLATFORM_ADMIN));

		Subject tenant = subject(Role.TENANT_ADMIN, Scope.tenant("t1"));
		assertEquals(Decision.ALLOW, authz.grantRole(tenant, target("t1", "p1"), Role.PROJECT_ADMIN));
		assertEquals(Decision.DENY, authz.grantRole(tenant, target("t1", "p1"), Role.TENANT_ADMIN));
		assertEquals(Decision.DENY, authz.grantRole(tenant, target("t1", "p1"), Role.PLATFORM_ADMIN));
		assertEquals(Decision.DENY, authz.grantRole(tenant, target("t2", "p2"), Role.PROJECT_VIEWER));

		Subject project = subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1"));
		assertEquals(Decision.ALLOW, authz.grantRole(project, target("t1", "p1"), Role.PROJECT_VIEWER));
		assertEquals(Decision.DENY, authz.grantRole(project, target("t1", "p1"), Role.PROJECT_ADMIN));
		assertEquals(Decision.DENY, authz.grantRole(project, target("t1", "p1"), Role.TENANT_ADMIN));
		assertEquals(Decision.DENY, authz.grantRole(project, target("t1", "p2"), Role.PROJECT_VIEWER));

		Subject viewer = subject(Role.PROJECT_VIEWER, Scope.project("t1", "p1"));
		assertEquals(Decision.DENY, authz.grantRole(viewer, target("t1", "p1"), Role.PROJECT_VIEWER));
	}

	@Test
	void bodyReadFollowsThreeTierVisibility() {
		var tenantTarget = target("t1", "");
		var projectTarget = target("t1", "p1");
		assertAllowed(subject(Role.PLATFORM_ADMIN, Scope.platform()), Action.BODY_READ, projectTarget);
		assertAllowed(subject(Role.TENANT_ADMIN, Scope.tenant("t1")), Action.BODY_READ, tenantTarget);
		assertAllowed(subject(Role.TENANT_ADMIN, Scope.tenant("t1")), Action.BODY_READ, projectTarget);
		assertAllowed(subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1")), Action.BODY_READ, projectTarget);
		assertDenied(subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1")), Action.BODY_READ, target("t1", "p2"));
		assertDenied(subject(Role.TENANT_ADMIN, Scope.tenant("t2")), Action.BODY_READ, tenantTarget);
		assertDenied(subject(Role.PROJECT_VIEWER, Scope.project("t1", "p1")), Action.BODY_READ, projectTarget);
	}

	@Test
	void bodyExportIsPlatformAdminOnly() {
		var projectTarget = target("t1", "p1");
		assertAllowed(subject(Role.PLATFORM_ADMIN, Scope.platform()), Action.BODY_EXPORT, projectTarget);
		assertDenied(subject(Role.TENANT_ADMIN, Scope.tenant("t1")), Action.BODY_EXPORT, projectTarget);
		assertDenied(subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1")), Action.BODY_EXPORT, projectTarget);
		assertDenied(subject(Role.PROJECT_VIEWER, Scope.project("t1", "p1")), Action.BODY_EXPORT, projectTarget);
	}

	/** 任务6 授权细化：账务写/价格发布仅平台管理员；scope 内读对租户/项目角色开放；观察者只读。 */
	@Test
	void billingAndPriceAndRecordingActionsFollowTheControlPlaneMatrix() {
		var platform = subject(Role.PLATFORM_ADMIN, new Scope("", ""));
		var tenantAdmin = subject(Role.TENANT_ADMIN, new Scope("t1", ""));
		var projectAdmin = subject(Role.PROJECT_ADMIN, new Scope("t1", "p1"));
		var viewer = subject(Role.PROJECT_VIEWER, new Scope("t1", "p1"));

		// 账务写（BILLING_MANAGE）与价格发布（PRICE_PUBLISH）：仅平台管理员。
		assertAllowed(platform, Action.BILLING_MANAGE, target("t1", "p1"));
		assertAllowed(platform, Action.PRICE_PUBLISH, target("", ""));
		for (var subject : List.of(tenantAdmin, projectAdmin, viewer)) {
			assertDenied(subject, Action.BILLING_MANAGE, target("t1", "p1"));
			assertDenied(subject, Action.PRICE_PUBLISH, target("t1", ""));
		}

		// scope 内读：租户/项目管理员与观察者均可。
		for (var subject : List.of(tenantAdmin, projectAdmin, viewer)) {
			assertAllowed(subject, Action.BILLING_READ, target("t1", "p1"));
			assertAllowed(subject, Action.RECORDING_READ, target("t1", "p1"));
		}

		// 越权 scope 读拒绝：其他租户/其他项目。
		assertDenied(tenantAdmin, Action.BILLING_READ, target("t2", ""));
		assertDenied(tenantAdmin, Action.RECORDING_READ, target("t2", ""));
		assertDenied(projectAdmin, Action.BILLING_READ, target("t1", "p2"));
		assertDenied(viewer, Action.BILLING_READ, target("t1", "p2"));

		// 伪造 scope（观察者带他人 scope）不因动作可读而放行：仍按真实 scope 判定。
		assertDenied(subject(Role.PROJECT_VIEWER, new Scope("t2", "p9")), Action.RECORDING_READ, target("t1", "p1"));
	}

}