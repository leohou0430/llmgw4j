package io.github.leohou0430.llmgw.identity;

/** Salted password hashing SPI (never plaintext, never a reversible encoding). */
public interface PasswordHasher {

	/** Returns a self-describing encoded hash (algorithm + params + salt + digest). */
	String hash(String rawPassword);

	/** Constant-time verification of a raw password against an encoded hash. */
	boolean verify(String rawPassword, String encodedHash);

}