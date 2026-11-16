package io.github.leohou0430.llmgw.identity;

/**
 * A newly issued credential: {@code plaintext} is returned exactly once (create/rotate
 * response) and never again; {@code masked} is the only form shown on subsequent reads.
 */
public record IssuedCredential(String plaintext, String masked) {
}