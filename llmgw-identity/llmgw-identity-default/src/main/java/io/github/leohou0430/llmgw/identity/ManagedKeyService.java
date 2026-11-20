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
 * caller's subject/target; the plaintext is returned once at create and only a masked
 * form is ever produced on read.
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
		if (key.id() == null || key.id().isBlank() || key.name() == null || key.name().isBlank()
				|| key.quotaTokens() < 0)
			throw new IllegalArgumentException("valid key id, name and nonnegative quota required");
		if (target.tenantId() == null || target.tenantId().isBlank() || target.projectId() == null
				|| target.projectId().isBlank())
			throw new IllegalArgumentException("tenant and project required");
		if (store.load().values().stream().anyMatch(stored -> stored.key().id().equals(key.id())))
			throw new IllegalArgumentException("key id already exists");
		IssuedCredential issued = issuer.issue();
		var metadata = new java.util.LinkedHashMap<>(key.metadata());
		metadata.put("llmgw.tenantId", target.tenantId());
		metadata.put("llmgw.projectId", target.projectId());
		store.upsert(issued.plaintext(),
				new ApiKeyRegistry.ApiKey(key.id(), key.name(), key.quotaTokens(), key.allowedModels(),
						key.expiresAtEpochMs(), key.group(), key.budgetMicroUsd(), java.util.Map.copyOf(metadata)));
		return issued;
	}

	public String readMasked(Subject actor, Target target, String keyId) {
		requireKeyManage(actor, target);
		ownedEntry(target, keyId);
		return issuer.displayForRead(keyId);
	}

	/** Lists stored keys as masked summaries (id/name only; never the plaintext). */
	public java.util.List<java.util.Map<String, Object>> list(Subject actor, Target target) {
		requireKeyManage(actor, target);
		return store.load()
			.values()
			.stream()
			.filter(stored -> stored.rotatedTo() == null && owns(stored.key(), target))
			.map(stored -> java.util.Map.<String, Object>of("id", stored.key().id(), "name", stored.key().name(),
					"masked", "****"))
			.toList();
	}

	public boolean revoke(Subject actor, Target target, String keyId) {
		requireKeyManage(actor, target);
		var entry = ownedEntry(target, keyId);
		store.load()
			.entrySet()
			.stream()
			.filter(candidate -> candidate.getValue().key().id().equals(keyId)
					&& owns(candidate.getValue().key(), target))
			.map(java.util.Map.Entry::getKey)
			.toList()
			.forEach(store::deleteAtRest);
		return true;
	}

	public IssuedCredential rotate(Subject actor, Target target, String keyId) {
		requireKeyManage(actor, target);
		var entry = ownedEntry(target, keyId);
		var previous = store.load()
			.entrySet()
			.stream()
			.filter(candidate -> candidate.getValue().key().id().equals(keyId)
					&& owns(candidate.getValue().key(), target))
			.map(java.util.Map.Entry::getKey)
			.toList();
		IssuedCredential issued = issuer.issue();
		store.upsert(issued.plaintext(), entry.getValue().key());
		previous.forEach(store::deleteAtRest);
		return issued;
	}

	public boolean belongsTo(String keyId, Target target) {
		return store.load()
			.values()
			.stream()
			.anyMatch(value -> value.key().id().equals(keyId) && owns(value.key(), target));
	}

	private java.util.Map.Entry<String, KeyStore.StoredKey> ownedEntry(Target target, String keyId) {
		return store.load()
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue().key().id().equals(keyId) && owns(entry.getValue().key(), target))
			.findFirst()
			.orElseThrow(() -> new SecurityException("key not found in authorized scope"));
	}

	private static boolean owns(ApiKeyRegistry.ApiKey key, Target target) {
		return Objects.equals(target.tenantId(), key.metadata().get("llmgw.tenantId"))
				&& Objects.equals(target.projectId(), key.metadata().get("llmgw.projectId"));
	}

	private void requireKeyManage(Subject actor, Target target) {
		if (!authorization.decide(actor, Action.KEY_MANAGE, target).allowed()) {
			throw new SecurityException("key management is not authorized for this scope");
		}
	}

}
