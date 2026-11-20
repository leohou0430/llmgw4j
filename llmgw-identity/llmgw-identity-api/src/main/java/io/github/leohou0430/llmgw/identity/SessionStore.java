package io.github.leohou0430.llmgw.identity;

import java.util.Optional;

/**
 * Server-side session persistence. Expired sessions are invisible; revocation is
 * explicit.
 */
public interface SessionStore {

	Session create(String accountId, long ttlMillis, long nowEpochMs);

	/** Returns the session only if it exists and is not expired at {@code nowEpochMs}. */
	Optional<Session> find(String sessionId, long nowEpochMs);

	void revoke(String sessionId);

	/**
	 * Invalidates every live session of an account (e.g. after a password or permission
	 * change).
	 */
	void revokeAllForAccount(String accountId);

}