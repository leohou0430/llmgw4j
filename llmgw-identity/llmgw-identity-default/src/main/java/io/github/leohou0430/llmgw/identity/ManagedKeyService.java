package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyStore;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.IssuedCredential;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;

import java.util.Objects;

/**
 * Managed API-key lifecycle. Every operation checks KEY_MANAGE server-side against the
 * caller's subject/target; the plaintext is returned once at create and only a masked form
 * is ever produced on read.
 */
public final class ManagedKeyService {
    private final KeyStore store;
    private final AuthorizationService authorization;
    private final CredentialIssuer issuer;

    public ManagedKeyService(KeyStore store, AuthorizationService authorization, CredentialIssuer issuer) {
        this.store = Objects.requireNonNull(store, "store");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    public IssuedCredential create(Subject actor, Target target, ApiKeyRegistry.ApiKey key) {
        requireKeyManage(actor, target);
        IssuedCredential issued = issuer.issue();
        store.upsert(issued.plaintext(), key);
        return issued;
    }

    public String readMasked(Subject actor, Target target, String keyId) {
        requireKeyManage(actor, target);
        return issuer.displayForRead(keyId);
    }

    /** Lists stored keys as masked summaries (id/name only; never the plaintext). */
    public java.util.List<java.util.Map<String, Object>> list(Subject actor, Target target) {
        requireKeyManage(actor, target);
        return store.load().values().stream()
                .map(stored -> java.util.Map.<String, Object>of(
                        "id", stored.key().id(),
                        "name", stored.key().name(),
                        "masked", "****"))
                .toList();
    }

    public boolean revoke(Subject actor, Target target, String token) {
        requireKeyManage(actor, target);
        store.delete(token);
        return true;
    }

    private void requireKeyManage(Subject actor, Target target) {
        if (!authorization.decide(actor, Action.KEY_MANAGE, target).allowed()) {
            throw new SecurityException("key management is not authorized for this scope");
        }
    }
}