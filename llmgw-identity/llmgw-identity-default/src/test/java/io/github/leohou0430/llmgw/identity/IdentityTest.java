package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 seam: roles/scopes, salted PBKDF2 password hashing (verify correct / reject wrong,
 * distinct salts), a one-time bootstrap that creates exactly one platform admin with no
 * default credentials, and duplicate-username rejection.
 */
class IdentityTest {

    private static Pbkdf2PasswordHasher hasher() {
        return Pbkdf2PasswordHasher.withIterations(1000);
    }

    @Test
    void pbkdf2HashesAndVerifiesWithoutStoringPlaintext() {
        Pbkdf2PasswordHasher hasher = hasher();
        String encoded = hasher.hash("s3cret");
        assertNotEquals("s3cret", encoded);
        assertTrue(hasher.verify("s3cret", encoded));
        assertFalse(hasher.verify("wrong", encoded));
        assertNotEquals(encoded, hasher.hash("s3cret"), "each hash must use a fresh salt");
    }

    @Test
    void bootstrapCreatesExactlyOnePlatformAdmin() {
        AccountStore store = new InMemoryAccountStore();
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(store, hasher());

        Account admin = bootstrap.bootstrap("root", "change-me-now");

        assertEquals(Role.PLATFORM_ADMIN, admin.role());
        assertTrue(admin.scope().isPlatform());
        assertTrue(admin.enabled());
        assertNotEquals("change-me-now", admin.passwordHash());
        assertTrue(store.findByUsername("root").isPresent());
    }

    @Test
    void secondBootstrapIsRejected() {
        AccountStore store = new InMemoryAccountStore();
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(store, hasher());
        bootstrap.bootstrap("root", "pw-one");
        assertThrows(IllegalStateException.class, () -> bootstrap.bootstrap("root2", "pw-two"));
    }

    @Test
    void bootstrapRequiresExplicitNonBlankCredentials() {
        AccountStore store = new InMemoryAccountStore();
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(store, hasher());
        assertThrows(IllegalArgumentException.class, () -> bootstrap.bootstrap(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.bootstrap("root", ""));
    }

    @Test
    void duplicateUsernameIsRejected() {
        AccountStore store = new InMemoryAccountStore();
        store.create(new Account("1", "alice", "h", Role.PROJECT_VIEWER, Scope.project("t", "p"), true));
        assertThrows(IllegalArgumentException.class,
                () -> store.create(new Account("2", "alice", "h2", Role.PROJECT_VIEWER, Scope.project("t", "p"), true)));
    }

    @Test
    void scopeFactories() {
        assertTrue(Scope.platform().isPlatform());
        assertFalse(Scope.tenant("t1").isPlatform());
        assertEquals("t1", Scope.project("t1", "p1").tenantId());
        assertEquals("p1", Scope.project("t1", "p1").projectId());
    }
}