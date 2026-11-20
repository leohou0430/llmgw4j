package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Session identity and requested scope are resolved once, before reading scoped data. */
final class ControlAccess {

	static Account account(RequestAuthorizer auth, String sid) {
		return auth.account(sid)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session"));
	}

	static Target target(RequestAuthorizer auth, String sid, String tenant, String project) {
		var account = account(auth, sid);
		return new Target(tenant == null ? account.scope().tenantId() : tenant,
				project == null ? account.scope().projectId() : project);
	}

	static Target resolve(RequestAuthorizer auth, OrgStore orgs, String sid, String tenant, String project) {
		Target target = target(auth, sid, tenant, project);
		if (!target.tenantId().isBlank() && orgs.tenants(target.tenantId()).isEmpty())
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "unknown tenant");
		if (!target.projectId().isBlank() && orgs.projects(target.tenantId(), target.projectId()).isEmpty())
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project does not belong to tenant");
		return target;
	}

	static void require(RequestAuthorizer auth, String sid, String csrf, boolean write, Action action, Target target) {
		var outcome = auth.authorize(new AuthenticatedRequest(sid, csrf, write, action, target));
		if (outcome != AuthOutcome.ALLOW)
			throw new ResponseStatusException(
					outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
					outcome.name());
	}

	static void platform(RequestAuthorizer auth, String sid) {
		if (account(auth, sid).role() != Role.PLATFORM_ADMIN)
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "global configuration requires platform admin");
	}

	private ControlAccess() {
	}

}
