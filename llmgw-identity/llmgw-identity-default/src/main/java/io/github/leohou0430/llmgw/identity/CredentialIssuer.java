package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.IssuedCredential;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Issues API-key credentials: the plaintext is returned once at create/rotate and is never
 * again retrievable; reads only ever show a masked form that hides the middle of the token.
 */
public final class CredentialIssuer {
    private static final String PREFIX = "sk-";
    private final SecureRandom random = new SecureRandom();

    public IssuedCredential issue() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new IssuedCredential(token, mask(token));
    }

    /** Keeps the prefix and last four characters; hides everything between. */
    public static String mask(String token) {
        if (token == null || token.length() <= PREFIX.length() + 4) {
            return "****";
        }
        return PREFIX + "****" + token.substring(token.length() - 4);
    }

    /** The only representation a read/list may return — never the plaintext. */
    public String displayForRead(String keyId) {
        String suffix = keyId != null && keyId.length() >= 4 ? keyId.substring(keyId.length() - 4) : "";
        return "****" + suffix;
    }
}