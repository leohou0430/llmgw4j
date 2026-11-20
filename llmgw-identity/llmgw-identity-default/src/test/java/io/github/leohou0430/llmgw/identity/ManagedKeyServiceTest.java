package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyStore;
import io.github.leohou0430.llmgw.identity.IssuedCredential;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.4 seam: the managed key lifecycle enforces RBAC (server-side), returns plaintext
 * only once at create, and only ever exposes a masked form on read.
 */
class ManagedKeyServiceTest {

	private static final class MapKeyStore implements KeyStore {

		final Map<String, ApiKeyRegistry.ApiKey> keys = new LinkedHashMap<>();

		@Override
		public void upsert(String token, ApiKeyRegistry.ApiKey key) {
			keys.put(token, key);
		}

		@Override
		public void delete(String token) {
			keys.remove(token);
		}

		@Override
		public void deleteAtRest(String token) {
			keys.remove(token);
		}

		@Override
		public void rotate(String oldToken, String newToken, long overlapEnd) {
		}

		@Override
		public void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd) {
		}

		@Override
		public Map<String, StoredKey> load() {
			Map<String, StoredKey> result = new LinkedHashMap<>();
			keys.forEach((token, key) -> result.put(token, new StoredKey(key, null, 0)));
			return result;
		}

		@Override
		public boolean enabled() {
			return true;
		}

	}

	private static ApiKeyRegistry.ApiKey key(String id) {
		return new ApiKeyRegistry.ApiKey(id, "name", 1000L, Set.of("deepseek-chat"));
	}

	private static Subject platformAdmin() {
		return new Subject(Role.PLATFORM_ADMIN, Scope.platform());
	}

	@Test
	void authorizedCreateStoresKeyAndReturnsPlaintextOnce() {
		MapKeyStore store = new MapKeyStore();
		ManagedKeyService service = new ManagedKeyService(store, new RbacAuthorizationService(),
				new CredentialIssuer());

		IssuedCredential issued = service.create(platformAdmin(), new Target("t1", "p1"), key("k1"));

		assertNotNull(issued.plaintext());
		assertFalse(issued.plaintext().equals(issued.masked()));
		assertTrue(store.keys.containsKey(issued.plaintext()), "the issued token is persisted");
	}

	@Test
	void readNeverReturnsPlaintext() {
		MapKeyStore store = new MapKeyStore();
		ManagedKeyService service = new ManagedKeyService(store, new RbacAuthorizationService(),
				new CredentialIssuer());
		IssuedCredential issued = service.create(platformAdmin(), new Target("t1", "p1"), key("k1"));

		String masked = service.readMasked(platformAdmin(), new Target("t1", "p1"), "k1");
		assertTrue(masked.contains("*"));
		assertFalse(masked.equals(issued.plaintext()));
	}

	@Test
	void unauthorizedRoleIsRejected() {
		ManagedKeyService service = new ManagedKeyService(new MapKeyStore(), new RbacAuthorizationService(),
				new CredentialIssuer());
		Subject viewer = new Subject(Role.PROJECT_VIEWER, Scope.project("t1", "p1"));
		assertThrows(SecurityException.class, () -> service.create(viewer, new Target("t1", "p1"), key("k1")));
	}

	@Test
	void crossProjectAdminIsRejected() {
		ManagedKeyService service = new ManagedKeyService(new MapKeyStore(), new RbacAuthorizationService(),
				new CredentialIssuer());
		Subject otherProject = new Subject(Role.PROJECT_ADMIN, Scope.project("t1", "p2"));
		assertThrows(SecurityException.class, () -> service.create(otherProject, new Target("t1", "p1"), key("k1")));
	}

	@Test
	void revokeDeletesTheKey() {
		MapKeyStore store = new MapKeyStore();
		ManagedKeyService service = new ManagedKeyService(store, new RbacAuthorizationService(),
				new CredentialIssuer());
		IssuedCredential issued = service.create(platformAdmin(), new Target("t1", "p1"), key("k1"));

		assertTrue(service.revoke(platformAdmin(), new Target("t1", "p1"), "k1"));
		assertFalse(store.keys.containsKey(issued.plaintext()));
	}

	@Test
	void projectListExcludesOtherProjectsAndTenants() {
		MapKeyStore store = new MapKeyStore();
		ManagedKeyService service = new ManagedKeyService(store, new RbacAuthorizationService(),
				new CredentialIssuer());
		service.create(platformAdmin(), new Target("t1", "p1"), key("own"));
		service.create(platformAdmin(), new Target("t1", "p2"), key("other-project"));
		service.create(platformAdmin(), new Target("t2", "p1"), key("other-tenant"));
		store.upsert("legacy", key("unscoped"));
		var visible = service.list(new Subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1")), new Target("t1", "p1"));
		org.junit.jupiter.api.Assertions.assertEquals(1, visible.size());
		org.junit.jupiter.api.Assertions.assertEquals("own", visible.getFirst().get("id"));
	}

	@Test
	void forgedTargetCannotReadRotateOrRevokeForeignKey() {
		var store = new MapKeyStore();
		var service = new ManagedKeyService(store, new RbacAuthorizationService(), new CredentialIssuer());
		var issued = service.create(platformAdmin(), new Target("t2", "p2"), key("foreign"));
		var actor = new Subject(Role.PROJECT_ADMIN, Scope.project("t1", "p1"));
		var target = new Target("t1", "p1");
		assertThrows(SecurityException.class, () -> service.readMasked(actor, target, "foreign"));
		assertThrows(SecurityException.class, () -> service.rotate(actor, target, "foreign"));
		assertThrows(SecurityException.class, () -> service.revoke(actor, target, "foreign"));
		assertTrue(store.keys.containsKey(issued.plaintext()));
	}

	@Test
	void rotationPreservesIdentityScopeAndRevokesOldCredential() {
		var store = new MapKeyStore();
		var service = new ManagedKeyService(store, new RbacAuthorizationService(), new CredentialIssuer());
		var target = new Target("t1", "p1");
		var first = service.create(platformAdmin(), target, key("k1"));
		var second = service.rotate(platformAdmin(), target, "k1");
		assertFalse(store.keys.containsKey(first.plaintext()));
		assertTrue(store.keys.containsKey(second.plaintext()));
		org.junit.jupiter.api.Assertions.assertEquals("k1", store.keys.get(second.plaintext()).id());
		assertThrows(IllegalArgumentException.class,
				() -> service.create(platformAdmin(), new Target("t2", "p2"), key("k1")));
	}

}
