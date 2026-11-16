package io.github.leohou0430.llmgw.identity;

import java.util.Objects;

/**
 * A control-plane account. The password hash is the only credential material ever stored;
 * the raw password is never retained. {@code enabled=false} disables login without
 * deleting the record.
 */
public record Account(
        String id,
        String username,
        String passwordHash,
        Role role,
        Scope scope,
        boolean enabled) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(scope, "scope");
    }

    public Account disabled() {
        return new Account(id, username, passwordHash, role, scope, false);
    }
}