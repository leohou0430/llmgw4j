package io.github.leohou0430.llmgw.identity;

/**
 * Server-side authorization. Every control-plane endpoint must call {@link #decide} with
 * the caller's subject, the action and the target object; credentials and password hashes
 * are never readable by any role. {@link #grantRole} enforces that a caller cannot grant
 * a role at or above its own level (project admins may only add lower-privileged
 * members).
 */
public interface AuthorizationService {

	Decision decide(Subject subject, Action action, Target target);

	Decision grantRole(Subject actor, Target target, Role grantedRole);

}