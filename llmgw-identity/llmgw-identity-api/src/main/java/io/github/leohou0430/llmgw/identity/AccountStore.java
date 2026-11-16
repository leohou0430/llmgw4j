package io.github.leohou0430.llmgw.identity;

import java.util.Optional;

/** Persistence SPI for accounts. Implementations own the durable form; the raw password is never passed here. */
public interface AccountStore {

    /** Stores a new account; a duplicate username is rejected. */
    Account create(Account account);

    Optional<Account> findByUsername(String username);

    /** Lookup by account id (used to authorize a session's owning account). */
    Optional<Account> findById(String accountId);

    /** Replaces the password hash for an existing account (invalidating sessions is T4.2). */
    void updatePassword(String username, String newPasswordHash);

    default java.util.List<Account> list() { return java.util.List.of(); }

    /** True once any account exists (guards one-time bootstrap). */
    boolean existsAny();
}
