package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Session;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.2 seam: server-side sessions (create / find / expiry / revoke / revoke-all), a
 * session-bound CSRF token (tamper and wrong-session rejected), a fixed-window login rate
 * limiter, and password change invalidating live sessions.
 */
class SessionTest {

	private static Account account(String id, String username, String hash) {
		return new Account(id, username, hash, Role.PROJECT_ADMIN, Scope.project("t", "p"), true);
	}

	@Test
	void sessionCreateFindExpireAndRevoke() {
		InMemorySessionStore store = new InMemorySessionStore();
		Session session = store.create("acct-1", 1000L, 0L);

		assertTrue(store.find(session.id(), 500L).isPresent());
		assertTrue(store.find(session.id(), 1500L).isEmpty(), "expired session is invisible");

		store.revoke(session.id());
		assertTrue(store.find(session.id(), 0L).isEmpty());
	}

	@Test
	void revokeAllForAccountDropsEverySession() {
		InMemorySessionStore store = new InMemorySessionStore();
		Session a = store.create("acct-1", 1000L, 0L);
		Session b = store.create("acct-1", 1000L, 0L);
		store.revokeAllForAccount("acct-1");
		assertTrue(store.find(a.id(), 0L).isEmpty());
		assertTrue(store.find(b.id(), 0L).isEmpty());
	}

	@Test
	void rateLimiterBlocksAfterMaxWithinWindow() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, 1000L);
		assertTrue(limiter.allow("u", 0L));
		assertTrue(limiter.allow("u", 10L));
		assertTrue(limiter.allow("u", 20L));
		assertFalse(limiter.allow("u", 30L), "fourth attempt in the window is blocked");
		assertTrue(limiter.allow("u", 2000L), "new window resets the budget");
	}

	@Test
	void csrfTokenIsSessionBoundAndTamperRejected() {
		HmacCsrfTokenService csrf = HmacCsrfTokenService.randomKey();
		String token = csrf.issue("session-1");
		assertTrue(csrf.verify("session-1", token));
		assertFalse(csrf.verify("session-2", token), "token bound to a different session");
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
		assertFalse(csrf.verify("session-1", tampered));
	}

	@Test
	void loginCreatesSessionOnlyForCorrectPassword() {
		Pbkdf2PasswordHasher hasher = Pbkdf2PasswordHasher.withIterations(1000);
		Account account = account("acct-1", "alice", hasher.hash("secret"));
		SessionService service = new SessionService(new InMemorySessionStore(), hasher, 60_000L);

		assertTrue(service.login(account, "secret", 0L).isPresent());
		assertTrue(service.login(account, "wrong", 0L).isEmpty());
		assertTrue(service.login(account.disabled(), "secret", 0L).isEmpty(), "disabled account cannot log in");
	}

	@Test
	void changePasswordInvalidatesLiveSessions() {
		Pbkdf2PasswordHasher hasher = Pbkdf2PasswordHasher.withIterations(1000);
		InMemoryAccountStore accounts = new InMemoryAccountStore();
		Account account = accounts.create(account("acct-1", "alice", hasher.hash("old")));
		InMemorySessionStore sessions = new InMemorySessionStore();
		SessionService service = new SessionService(sessions, hasher, 60_000L);

		Session session = service.login(account, "old", 0L).orElseThrow();
		service.changePassword(accounts, "alice", "new");

		assertTrue(sessions.find(session.id(), 0L).isEmpty(), "password change revokes live sessions");
		Optional<Account> updated = accounts.findByUsername("alice");
		assertTrue(updated.isPresent());
		assertTrue(hasher.verify("new", updated.get().passwordHash()));
	}

}