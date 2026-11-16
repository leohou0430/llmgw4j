package io.github.leohou0430.llmgw.identity;

import java.util.Objects;

/** A caller: a role plus the scope it is bound to. */
public record Subject(Role role, Scope scope) {

    public Subject {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(scope, "scope");
    }
}