package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.IssuedCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.4 seam (pure core): a key's plaintext is returned once at create/rotate and masked on
 * reads; the mask hides the middle of the token.
 */
class CredentialIssuerTest {

    private final CredentialIssuer issuer = new CredentialIssuer();

    @Test
    void issueReturnsPlaintextOnceAndAMaskedForm() {
        IssuedCredential issued = issuer.issue();
        assertTrue(issued.plaintext().startsWith("sk-"));
        assertTrue(issued.masked().contains("*"));
        assertNotEquals(issued.plaintext(), issued.masked());
    }

    @Test
    void maskHidesTheMiddleOfTheToken() {
        String masked = CredentialIssuer.mask("sk-ABCDEFGHIJKLMNOP");
        assertTrue(masked.startsWith("sk-"));
        assertTrue(masked.endsWith("MNOP"));
        assertFalse(masked.contains("ABCDEFGHIJ"), "the middle of the secret must not be revealed");
    }

    @Test
    void readsNeverReturnPlaintext() {
        IssuedCredential issued = issuer.issue();
        String read = issuer.displayForRead("key-id-1234");
        assertTrue(read.contains("*"));
        assertNotEquals(issued.plaintext(), read);
    }

    @Test
    void issuesAreRandom() {
        assertNotEquals(issuer.issue().plaintext(), issuer.issue().plaintext());
    }
}