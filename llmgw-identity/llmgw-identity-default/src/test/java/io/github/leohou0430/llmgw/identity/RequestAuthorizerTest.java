package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.Target;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4.4/T4.10 seam: the request authorizer composes session validity + CSRF (for mutating
 * calls) + RBAC — unauthenticated, CSRF-failed, and forbidden are distinguished; reads do
 * not require CSRF.
 */
class RequestAuthorizerTest {

	private static Account account(String id, Role role, Scope scope, boolean enabled) {
		return new Account(id, "user-" + id, "hash", role, scope, enabled);
	}

	private static AuthOutcome authorize(AccountStore accounts, SessionStore sessions, RequestAuthorizer authorizer,
			String sessionId, String csrf, boolean mutating, Action action, Target target) {
		return authorizer.authorize(new AuthenticatedRequest(sessionId, csrf, mutating, action, target));
	}

	@Test
	void unauthenticatedWhenSessionMissing() {
		AccountStore accounts = new InMemoryAccountStore();
		SessionStore sessions = new InMemorySessionStore();
		RequestAuthorizer authorizer = new RequestAuthorizer(sessions, HmacCsrfTokenService.randomKey(), accounts,
				new RbacAuthorizationService(), () -> 0L);
		assertEquals(AuthOutcome.UNAUTHENTICATED, authorize(accounts, sessions, authorizer, "nope", null, false,
				Action.KEY_MANAGE, new Target("t", "p")));
	}

	@Test
	void csrfRequiredForMutatingButNotForRead() {
		AccountStore accounts = new InMemoryAccountStore();
		accounts.create(account("a1", Role.PLATFORM_ADMIN, Scope.platform(), true));
		SessionStore sessions = new InMemorySessionStore();
		HmacCsrfTokenService csrf = HmacCsrfTokenService.randomKey();
		RequestAuthorizer authorizer = new RequestAuthorizer(sessions, csrf, accounts, new RbacAuthorizationService(),
				() -> 0L);
		Session session = sessions.create("a1", 60_000L, 0L);

		assertEquals(AuthOutcome.CSRF_FAILED, authorize(accounts, sessions, authorizer, session.id(), "bad", true,
				Action.KEY_MANAGE, new Target("t", "p")));
		assertEquals(AuthOutcome.ALLOW, authorize(accounts, sessions, authorizer, session.id(),
				csrf.issue(session.id()), true, Action.KEY_MANAGE, new Target("t", "p")));
		assertEquals(AuthOutcome.ALLOW, authorize(accounts, sessions, authorizer, session.id(), null, false,
				Action.KEY_MANAGE, new Target("t", "p")));
	}

	@Test
	void forbiddenByRbac() {
		AccountStore accounts = new InMemoryAccountStore();
		accounts.create(account("v1", Role.PROJECT_VIEWER, Scope.project("t", "p"), true));
		SessionStore sessions = new InMemorySessionStore();
		RequestAuthorizer authorizer = new RequestAuthorizer(sessions, HmacCsrfTokenService.randomKey(), accounts,
				new RbacAuthorizationService(), () -> 0L);
		Session session = sessions.create("v1", 60_000L, 0L);

		assertEquals(AuthOutcome.FORBIDDEN, authorize(accounts, sessions, authorizer, session.id(), null, false,
				Action.KEY_MANAGE, new Target("t", "p")));
	}

	@Test
	void disabledAccountIsUnauthenticated() {
		AccountStore accounts = new InMemoryAccountStore();
		accounts.create(account("d1", Role.PLATFORM_ADMIN, Scope.platform(), false));
		SessionStore sessions = new InMemorySessionStore();
		RequestAuthorizer authorizer = new RequestAuthorizer(sessions, HmacCsrfTokenService.randomKey(), accounts,
				new RbacAuthorizationService(), () -> 0L);
		Session session = sessions.create("d1", 60_000L, 0L);

		assertEquals(AuthOutcome.UNAUTHENTICATED, authorize(accounts, sessions, authorizer, session.id(), null, false,
				Action.KEY_MANAGE, new Target("t", "p")));
	}

}