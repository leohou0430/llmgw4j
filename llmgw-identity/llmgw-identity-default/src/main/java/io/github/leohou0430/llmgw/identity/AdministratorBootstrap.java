package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.PasswordHasher;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;

import java.util.Objects;
import java.util.UUID;

/**
 * One-time local bootstrap of the first platform administrator. The caller must supply an
 * explicit username and password (there is no default credential and no public
 * self-registration); a second bootstrap is rejected once any account exists.
 */
public final class AdministratorBootstrap {
    private final AccountStore store;
    private final PasswordHasher hasher;

    public AdministratorBootstrap(AccountStore store, PasswordHasher hasher) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public Account bootstrap(String username, String rawPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (store.existsAny()) {
            throw new IllegalStateException("control plane is already bootstrapped");
        }
        Account admin = new Account(UUID.randomUUID().toString(), username,
                hasher.hash(rawPassword), Role.PLATFORM_ADMIN, Scope.platform(), true);
        return store.create(admin);
    }
}