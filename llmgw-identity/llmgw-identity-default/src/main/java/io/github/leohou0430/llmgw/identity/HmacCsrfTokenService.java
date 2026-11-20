package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.CsrfTokenService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** CSRF tokens bound to a session via an HMAC over session id + a random nonce. */
public final class HmacCsrfTokenService implements CsrfTokenService {

	private final byte[] key;

	private final SecureRandom random = new SecureRandom();

	public HmacCsrfTokenService(byte[] key) {
		this.key = key.clone();
	}

	public static HmacCsrfTokenService randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new HmacCsrfTokenService(key);
	}

	@Override
	public String issue(String sessionId) {
		byte[] nonce = new byte[16];
		random.nextBytes(nonce);
		String encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
		return encodedNonce + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sessionId + "|" + encodedNonce));
	}

	@Override
	public boolean verify(String sessionId, String token) {
		if (token == null) {
			return false;
		}
		int dot = token.indexOf('.');
		if (dot < 0) {
			return false;
		}
		String encodedNonce = token.substring(0, dot);
		String signature = token.substring(dot + 1);
		String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sessionId + "|" + encodedNonce));
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signature.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] mac(String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception failure) {
			throw new IllegalStateException("HMAC failure", failure);
		}
	}

}