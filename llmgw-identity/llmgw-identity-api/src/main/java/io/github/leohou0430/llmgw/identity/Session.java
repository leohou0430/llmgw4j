package io.github.leohou0430.llmgw.identity;

import java.util.Objects;

/**
 * A server-side login session. The {@code id} is an opaque high-entropy random value put
 * in an HttpOnly/Secure/SameSite cookie by the HTTP layer; it is never derived from
 * credentials. {@code tokenVersion} lets a permission change invalidate live sessions.
 */
public record Session(String id, String accountId, long expiresAtEpochMs, long tokenVersion) {

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
    }

    public boolean expired(long nowEpochMs) {
        return nowEpochMs >= expiresAtEpochMs;
    }
}