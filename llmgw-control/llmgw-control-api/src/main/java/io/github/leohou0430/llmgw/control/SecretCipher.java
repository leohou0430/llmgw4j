package io.github.leohou0430.llmgw.control;

/** Authenticated at-rest encryption for provider secrets (AEAD; tamper is rejected). */
public interface SecretCipher {

    /** Encrypts plaintext into a self-describing ciphertext string. */
    String encrypt(String plaintext);

    /** Decrypts a ciphertext produced by {@link #encrypt}; tamper or wrong key throws. */
    String decrypt(String ciphertext);
}