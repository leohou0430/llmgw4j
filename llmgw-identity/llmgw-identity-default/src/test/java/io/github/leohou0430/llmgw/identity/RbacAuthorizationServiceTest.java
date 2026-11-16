package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.Decision;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import org.junit.jupiter.api.Test;

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
        assertAllowed(platform, Action.MEMBER_GRANT, target("t1", "p1")); // sanity: platform can manage members
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
}