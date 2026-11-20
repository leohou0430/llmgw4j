package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link SessionStore}: 256-bit random ids; expired sessions are invisible. */
public final class InMemorySessionStore implements SessionStore {

	private final SecureRandom random = new SecureRandom();

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	@Override
	public Session create(String accountId, long ttlMillis, long nowEpochMs) {
		Objects.requireNonNull(accountId, "accountId");
		byte[] raw = new byte[32];
		random.nextBytes(raw);
		String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Session session = new Session(id, accountId, nowEpochMs + ttlMillis, 0L);
		sessions.put(id, session);
		return session;
	}

	@Override
	public Optional<Session> find(String sessionId, long nowEpochMs) {
		Session session = sessionId == null ? null : sessions.get(sessionId);
		if (session == null || session.expired(nowEpochMs)) {
			return Optional.empty();
		}
		return Optional.of(session);
	}

	@Override
	public void revoke(String sessionId) {
		sessions.remove(sessionId);
	}

	@Override
	public void revokeAllForAccount(String accountId) {
		sessions.values().removeIf(session -> session.accountId().equals(accountId));
	}

}