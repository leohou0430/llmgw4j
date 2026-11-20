package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RootKeyMissingException;
import io.github.leohou0430.llmgw.control.SecretCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.7 seam: provider secrets are encrypted at rest with an externally injected root key.
 * Round-trip works, a different key fails authentication, a tampered ciphertext is
 * rejected, and a missing root key fails closed (never plaintext).
 */
class SecretCipherTest {

	private static byte[] key(byte seed) {
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) (seed + i);
		}
		return key;
	}

	@Test
	void encryptDecryptRoundTrip() {
		SecretCipher cipher = new AesGcmSecretCipher(new StaticRootKey(key((byte) 1)));
		String encrypted = cipher.encrypt("sk-provider-secret");
		assertNotEquals("sk-provider-secret", encrypted);
		assertEquals("sk-provider-secret", cipher.decrypt(encrypted));
	}

	@Test
	void wrongKeyFailsAuthentication() {
		String encrypted = new AesGcmSecretCipher(new StaticRootKey(key((byte) 1))).encrypt("secret");
		SecretCipher other = new AesGcmSecretCipher(new StaticRootKey(key((byte) 9)));
		assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
	}

	@Test
	void tamperedCiphertextIsRejected() {
		SecretCipher cipher = new AesGcmSecretCipher(new StaticRootKey(key((byte) 1)));
		String encrypted = cipher.encrypt("secret");
		char last = encrypted.charAt(encrypted.length() - 1);
		String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == 'A' ? 'B' : 'A');
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
	}

	@Test
	void missingRootKeyFailsClosed() {
		SecretCipher cipher = new AesGcmSecretCipher(StaticRootKey.missing());
		assertThrows(RootKeyMissingException.class, () -> cipher.encrypt("secret"));
	}

}