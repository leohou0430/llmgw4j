package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RootKeyProvider;
import io.github.leohou0430.llmgw.control.SecretCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * AES-256-GCM (AEAD) at-rest cipher. A fresh 12-byte IV is generated per encryption and
 * the 128-bit tag authenticates the ciphertext, so tampering or a wrong root key fails.
 * The root key is fetched from an externally injected {@link RootKeyProvider} on every
 * operation; a missing key fails closed via {@code RootKeyMissingException}.
 */
public final class AesGcmSecretCipher implements SecretCipher {

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final RootKeyProvider rootKey;

	private final SecureRandom random = new SecureRandom();

	public AesGcmSecretCipher(RootKeyProvider rootKey) {
		this.rootKey = Objects.requireNonNull(rootKey, "rootKey");
	}

	@Override
	public String encrypt(String plaintext) {
		byte[] key = rootKey.requireRootKey();
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
			return Base64.getEncoder().encodeToString(combined);
		}
		catch (Exception failure) {
			throw new IllegalStateException("failed to encrypt secret", failure);
		}
	}

	@Override
	public String decrypt(String ciphertext) {
		byte[] key = rootKey.requireRootKey();
		try {
			byte[] combined = Base64.getDecoder().decode(ciphertext);
			byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
			byte[] body = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
		}
		catch (Exception failure) {
			throw new IllegalStateException("failed to decrypt/authenticate secret", failure);
		}
	}

}