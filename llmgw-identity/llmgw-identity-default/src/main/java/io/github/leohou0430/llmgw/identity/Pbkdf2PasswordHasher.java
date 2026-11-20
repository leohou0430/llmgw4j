package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.PasswordHasher;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * PBKDF2WithHmacSHA256 password hashing (JDK standard KDF; crypto primitive not
 * hand-rolled). Each hash uses a fresh 16-byte salt and is stored self-describing as
 * {@code pbkdf2$<iterations>$<salt>$<digest>}. Verification is constant-time.
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

	private static final int SALT_BYTES = 16;

	private static final int KEY_BITS = 256;

	private final int iterations;

	private final SecureRandom random = new SecureRandom();

	public Pbkdf2PasswordHasher(int iterations) {
		if (iterations < 1) {
			throw new IllegalArgumentException("iterations must be positive");
		}
		this.iterations = iterations;
	}

	public static Pbkdf2PasswordHasher withIterations(int iterations) {
		return new Pbkdf2PasswordHasher(iterations);
	}

	@Override
	public String hash(String rawPassword) {
		Objects.requireNonNull(rawPassword, "rawPassword");
		byte[] salt = new byte[SALT_BYTES];
		random.nextBytes(salt);
		byte[] digest = pbkdf2(rawPassword.toCharArray(), salt, iterations);
		return "pbkdf2$" + iterations + "$" + encode(salt) + "$" + encode(digest);
	}

	@Override
	public boolean verify(String rawPassword, String encodedHash) {
		if (rawPassword == null || encodedHash == null) {
			return false;
		}
		String[] parts = encodedHash.split("\\$", -1);
		if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
			return false;
		}
		try {
			int storedIterations = Integer.parseInt(parts[1]);
			byte[] salt = Base64.getDecoder().decode(parts[2]);
			byte[] expected = Base64.getDecoder().decode(parts[3]);
			byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, storedIterations);
			return MessageDigest.isEqual(expected, actual);
		}
		catch (RuntimeException failure) {
			return false;
		}
	}

	private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
			return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
		}
		catch (Exception failure) {
			throw new IllegalStateException("PBKDF2 failure", failure);
		}
	}

	private static String encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

}