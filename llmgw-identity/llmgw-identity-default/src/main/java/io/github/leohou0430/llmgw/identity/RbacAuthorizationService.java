package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.Decision;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;

import java.util.Objects;
import java.util.Set;

/**
 * RBAC policy per the control-plane matrix. A platform admin acts globally; a tenant
 * admin only within its tenant and never for platform-only actions (tenant management and
 * global upstream sources); a project admin only within its project (and cannot manage
 * global sources or grant peer/higher roles); a project viewer is read-only within its
 * project. Credentials and password hashes are never readable by any role. Any
 * cross-tenant or out-of-scope target is denied uniformly.
 */
public final class RbacAuthorizationService implements AuthorizationService {

	private static final Set<Action> VIEWER_READS = Set.of(Action.CATALOG_READ, Action.USAGE_READ, Action.AUDIT_READ,
			Action.BILLING_READ, Action.RECORDING_READ, Action.APPROVAL_READ);

	private static final Set<Action> PLATFORM_ONLY = Set.of(Action.TENANT_MANAGE, Action.SOURCE_MANAGE,
			Action.BILLING_MANAGE, Action.PRICE_PUBLISH);

	/** 正文导出是数据离开控制面边界的动作，仅平台管理员可发起。 */
	private static final Set<Action> PLATFORM_ONLY_EXPORT = Set.of(Action.BODY_EXPORT);

	@Override
	public Decision decide(Subject subject, Action action, Target target) {
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(target, "target");
		if (action == Action.READ_CREDENTIAL || action == Action.READ_PASSWORD_HASH) {
			return Decision.DENY;
		}
		// 正文导出仅平台管理员（数据离开边界，写入管理审计）。
		if (action == Action.BODY_EXPORT) {
			return subject.role() == Role.PLATFORM_ADMIN ? Decision.ALLOW : Decision.DENY;
		}
		return switch (subject.role()) {
			case PLATFORM_ADMIN -> Decision.ALLOW;
			case TENANT_ADMIN ->
				Objects.equals(subject.scope().tenantId(), target.tenantId()) && !PLATFORM_ONLY.contains(action)
						&& !PLATFORM_ONLY_EXPORT.contains(action) ? Decision.ALLOW : Decision.DENY;
			case PROJECT_ADMIN -> sameProject(subject.scope(), target) && !PLATFORM_ONLY.contains(action)
					&& !PLATFORM_ONLY_EXPORT.contains(action) ? Decision.ALLOW : Decision.DENY;
			case PROJECT_VIEWER ->
				sameProject(subject.scope(), target) && VIEWER_READS.contains(action) ? Decision.ALLOW : Decision.DENY;
		};
	}

	@Override
	public Decision grantRole(Subject actor, Target target, Role grantedRole) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(grantedRole, "grantedRole");
		return switch (actor.role()) {
			case PLATFORM_ADMIN -> Decision.ALLOW;
			case TENANT_ADMIN -> Objects.equals(actor.scope().tenantId(), target.tenantId())
					&& (grantedRole == Role.PROJECT_ADMIN || grantedRole == Role.PROJECT_VIEWER) ? Decision.ALLOW
							: Decision.DENY;
			case PROJECT_ADMIN -> sameProject(actor.scope(), target) && grantedRole == Role.PROJECT_VIEWER
					? Decision.ALLOW : Decision.DENY;
			case PROJECT_VIEWER -> Decision.DENY;
		};
	}

	private static boolean sameProject(Scope scope, Target target) {
		return Objects.equals(scope.tenantId(), target.tenantId())
				&& Objects.equals(scope.projectId(), target.projectId());
	}

}