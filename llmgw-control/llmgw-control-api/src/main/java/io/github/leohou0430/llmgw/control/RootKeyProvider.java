package io.github.leohou0430.llmgw.control;

/**
 * Supplies the at-rest encryption root key, injected from a secure source OUTSIDE the
 * database (env/secret store/file KMS). When absent, callers must fail closed with
 * {@link RootKeyMissingException} rather than store plaintext.
 */
@FunctionalInterface
public interface RootKeyProvider {

    byte[] requireRootKey() throws RootKeyMissingException;
}