package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.PasswordHasher;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Login and password-change orchestration. A session is created only for an enabled
 * account with a correct password; a password change rehashes and invalidates every live
 * session of the account.
 */
public final class SessionService {

	private final SessionStore sessions;

	private final PasswordHasher hasher;

	private final long sessionTtlMillis;

	public SessionService(SessionStore sessions, PasswordHasher hasher, long sessionTtlMillis) {
		this.sessions = Objects.requireNonNull(sessions, "sessions");
		this.hasher = Objects.requireNonNull(hasher, "hasher");
		this.sessionTtlMillis = sessionTtlMillis;
	}

	public Optional<Session> login(Account account, String rawPassword, long nowEpochMs) {
		if (account == null || !account.enabled()) {
			return Optional.empty();
		}
		if (!hasher.verify(rawPassword, account.passwordHash())) {
			return Optional.empty();
		}
		return Optional.of(sessions.create(account.id(), sessionTtlMillis, nowEpochMs));
	}

	public void changePassword(AccountStore accounts, String username, String newRawPassword) {
		accounts.findByUsername(username).ifPresent(account -> {
			accounts.updatePassword(username, hasher.hash(newRawPassword));
			sessions.revokeAllForAccount(account.id());
		});
	}

}