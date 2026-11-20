package io.github.leohou0430.llmgw.conversion;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续轮信封合同（矩阵 §7.3 / §10.1）：期望值取自规格与已知 SHA-256 字面量， 独立构造的 token 用 JDK Mac 按规格约定生成，不复用被测实现。
 */
class ContinuationEnvelopeTest {

	private static final String PREFIX = "llmgw1:";

	private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

	private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

	private static final String PROJECT = "proj-1";

	private static final String ITEM = "item-1";

	private static final String TEXT = "abc";

	private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

	private static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private final ContinuationEnvelope envelope = new ContinuationEnvelope(KEY);

	@Test
	void mintThenVerifyRoundTrip() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);

		assertDoesNotThrow(() -> envelope.verify(token, PROJECT, TEXT));
	}

	@Test
	void emptyReasoningTextRoundTripUsesKnownEmptyDigest() {
		String token = envelope.mint(PROJECT, ITEM, "");

		assertDoesNotThrow(() -> envelope.verify(token, PROJECT, ""));
		assertEquals("{\"projectId\":\"proj-1\",\"itemId\":\"item-1\",\"reasoningSha256\":\"" + SHA256_EMPTY + "\"}",
				payloadJson(token), "空串推理的摘要必须是已知的 SHA-256(\"\")");
	}

	@Test
	void unicodeReasoningTextRoundTrip() {
		String text = "先核对上游字段，再回传推理。\uD83D\uDE80";

		String token = envelope.mint(PROJECT, ITEM, text);

		assertDoesNotThrow(() -> envelope.verify(token, PROJECT, text));
		assertRejected(token, PROJECT, text + "。");
	}

	@Test
	void mintedTokenCarriesNamespaceAndSpecPayloadOnly() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);

		assertTrue(token.startsWith(PREFIX), "信封必须带显式网关命名空间，不冒充供应商签名");
		String body = token.substring(PREFIX.length());
		String[] parts = body.split("\\.", -1);
		assertEquals(2, parts.length, "形态必须是 payload.hmac");
		assertFalse(token.contains("="), "base64url 不带填充");
		assertFalse(token.contains("+"), "必须是 url 安全字母表");
		assertFalse(token.contains("/"), "必须是 url 安全字母表");
		assertEquals("{\"projectId\":\"proj-1\",\"itemId\":\"item-1\",\"reasoningSha256\":\"" + SHA256_ABC + "\"}",
				new String(decode(parts[0]), StandardCharsets.UTF_8), "payload 是紧凑 JSON，只含项目绑定/条目标识/推理摘要");
		assertEquals(32, decode(parts[1]).length, "HMAC-SHA256 输出 32 字节");
		assertFalse(token.contains(TEXT), "明文推理不入信封，避免膨胀");
	}

	@Test
	void verifyAcceptsIndependentlyConstructedToken() throws Exception {
		String payload = "{\"projectId\":\"proj-1\",\"itemId\":\"item-1\",\"reasoningSha256\":\"" + SHA256_ABC + "\"}";
		String payloadB64 = encode(payload.getBytes(StandardCharsets.UTF_8));
		String token = PREFIX + payloadB64 + "." + encode(mac(KEY, PREFIX + payloadB64));

		assertDoesNotThrow(() -> envelope.verify(token, PROJECT, TEXT));
	}

	@Test
	void verifyRejectsHmacComputedWithoutPrefix() throws Exception {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String payloadB64 = token.substring(PREFIX.length()).split("\\.", -1)[0];
		String forged = PREFIX + payloadB64 + "." + encode(mac(KEY, payloadB64));

		assertRejected(forged, PROJECT, TEXT);
	}

	@Test
	void verifyRejectsTamperedPayload() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
		String tamperedPayload = "{\"projectId\":\"proj-2\",\"itemId\":\"item-1\",\"reasoningSha256\":\"" + SHA256_ABC
				+ "\"}";
		String forged = PREFIX + encode(tamperedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];

		assertRejected(forged, PROJECT, TEXT);
	}

	@Test
	void verifyRejectsSingleBytePayloadTamper() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
		byte[] payload = decode(parts[0]);
		payload[payload.length - 2] ^= 0x01;

		assertRejected(PREFIX + encode(payload) + "." + parts[1], PROJECT, TEXT);
	}

	@Test
	void verifyRejectsTamperedHmac() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
		byte[] hmac = decode(parts[1]);
		hmac[0] ^= 0x01;

		assertRejected(PREFIX + parts[0] + "." + encode(hmac), PROJECT, TEXT);
	}

	@Test
	void verifyRejectsTruncatedOrExtendedHmac() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
		byte[] hmac = decode(parts[1]);

		assertRejected(PREFIX + parts[0] + "." + encode(Arrays.copyOf(hmac, 16)), PROJECT, TEXT);
		assertRejected(PREFIX + parts[0] + "." + encode(Arrays.copyOf(hmac, 33)), PROJECT, TEXT);
		assertRejected(PREFIX + parts[0] + ".", PROJECT, TEXT);
	}

	@Test
	void verifyRejectsCrossProjectUse() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);

		assertRejected(token, "proj-2", TEXT);
	}

	@Test
	void verifyRejectsTamperedReasoningText() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);

		assertRejected(token, PROJECT, "abd");
		assertRejected(token, PROJECT, TEXT + " ");
		assertRejected(token, PROJECT, "");
	}

	@Test
	void verifyRejectsMissingToken() {
		assertRejected(null, PROJECT, TEXT);
		assertRejected("", PROJECT, TEXT);
		assertRejected("   ", PROJECT, TEXT);
	}

	@Test
	void verifyRejectsWrongNamespace() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String body = token.substring(PREFIX.length());

		assertRejected(body, PROJECT, TEXT);
		assertRejected("llmgw2:" + body, PROJECT, TEXT);
		assertRejected("LLMGW1:" + body, PROJECT, TEXT);
		assertRejected(" " + token, PROJECT, TEXT);
	}

	@Test
	void verifyRejectsMissingOrExtraSeparator() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String payloadB64 = token.substring(PREFIX.length()).split("\\.", -1)[0];

		assertRejected(PREFIX + payloadB64, PROJECT, TEXT);
		assertRejected(token + ".extra", PROJECT, TEXT);
		assertRejected(PREFIX + ".", PROJECT, TEXT);
	}

	@Test
	void verifyRejectsMalformedBase64() {
		assertRejected(PREFIX + "!!!.###", PROJECT, TEXT);
		assertRejected(PREFIX + "ab+cd.ef/gh", PROJECT, TEXT);
		assertRejected(PREFIX + "a.b", PROJECT, TEXT);
	}

	@Test
	void verifyRejectsMalformedPayloadJson() throws Exception {
		for (String payload : new String[] { "not json", "{", "[]", "\"text\"", "null" }) {
			String payloadB64 = encode(payload.getBytes(StandardCharsets.UTF_8));
			String token = PREFIX + payloadB64 + "." + encode(mac(KEY, PREFIX + payloadB64));

			assertRejected(token, PROJECT, TEXT);
		}
	}

	@Test
	void verifyRejectsPayloadWithMissingOrNonStringFields() throws Exception {
		String[] payloads = { "{}", "{\"projectId\":\"proj-1\",\"itemId\":\"item-1\"}",
				"{\"projectId\":\"proj-1\",\"itemId\":\"item-1\",\"reasoningSha256\":123}",
				"{\"projectId\":7,\"itemId\":\"item-1\",\"reasoningSha256\":\"" + SHA256_ABC + "\"}",
				"{\"projectId\":\"proj-1\",\"itemId\":\"item-1\",\"reasoningSha256\":\"zz\"}", };
		for (String payload : payloads) {
			String payloadB64 = encode(payload.getBytes(StandardCharsets.UTF_8));
			String token = PREFIX + payloadB64 + "." + encode(mac(KEY, PREFIX + payloadB64));

			assertRejected(token, PROJECT, TEXT);
		}
	}

	@Test
	void verifyRejectsTokenFromAnotherKeyInstance() {
		String token = new ContinuationEnvelope(OTHER_KEY).mint(PROJECT, ITEM, TEXT);

		assertRejected(token, PROJECT, TEXT);
	}

	@Test
	void sameKeyInstancesInteropWithoutSharedState() {
		String token = new ContinuationEnvelope(KEY.clone()).mint(PROJECT, ITEM, TEXT);

		assertDoesNotThrow(() -> envelope.verify(token, PROJECT, TEXT));
	}

	@Test
	void constructorRejectsNullAndEmptyKey() {
		assertThrows(IllegalArgumentException.class, () -> new ContinuationEnvelope(null));
		assertThrows(IllegalArgumentException.class, () -> new ContinuationEnvelope(new byte[0]));
	}

	@Test
	void constructorCopiesKeyDefensively() {
		byte[] key = KEY.clone();
		ContinuationEnvelope isolated = new ContinuationEnvelope(key);
		String token = isolated.mint(PROJECT, ITEM, TEXT);

		Arrays.fill(key, (byte) 0);

		assertDoesNotThrow(() -> isolated.verify(token, PROJECT, TEXT));
		assertEquals(token, isolated.mint(PROJECT, ITEM, TEXT));
	}

	@Test
	void mintRejectsNullArguments() {
		assertThrows(IllegalArgumentException.class, () -> envelope.mint(null, ITEM, TEXT));
		assertThrows(IllegalArgumentException.class, () -> envelope.mint(PROJECT, null, TEXT));
		assertThrows(IllegalArgumentException.class, () -> envelope.mint(PROJECT, ITEM, null));
	}

	@Test
	void verifyRejectsNullProjectIdOrReasoningText() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);

		assertRejected(token, null, TEXT);
		assertRejected(token, PROJECT, null);
	}

	@Test
	void failureIsClientSafeContinuationInvalid() {
		String token = envelope.mint(PROJECT, ITEM, TEXT);
		String keyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(KEY);

		CapabilityException failure = assertThrows(CapabilityException.class,
				() -> envelope.verify(token, "proj-2", TEXT));

		assertEquals(400, failure.status());
		assertEquals(1, failure.violations().size());
		Violation violation = failure.violations().get(0);
		assertEquals(CapabilityException.CONTINUATION_INVALID, violation.code());
		assertEquals("capability_continuation_invalid", violation.code());
		assertEquals("continuation", violation.fieldPath());
		assertTrue(violation.reason().matches(".*[\\u4e00-\\u9fa5].*"), "reason 必须是简洁中文");
		assertFalse(violation.reason().contains(keyB64), "reason 不得泄露密钥");
		assertFalse(violation.reason().contains("Exception"), "reason 不得拼底层异常栈");
		assertFalse(failure.getMessage().contains(keyB64), "面向客户端的消息不得泄露密钥");
	}

	private void assertRejected(String token, String projectId, String reasoningText) {
		CapabilityException failure = assertThrows(CapabilityException.class,
				() -> envelope.verify(token, projectId, reasoningText),
				"必须拒绝并落 capability_continuation_invalid，而不是裸 RuntimeException: " + token);
		assertEquals(400, failure.status());
		assertEquals(CapabilityException.CONTINUATION_INVALID, failure.violations().get(0).code());
		assertEquals("continuation", failure.violations().get(0).fieldPath());
		assertFalse(failure.violations().get(0).reason().isBlank());
	}

	private static String payloadJson(String token) {
		return new String(decode(token.substring(PREFIX.length()).split("\\.", -1)[0]), StandardCharsets.UTF_8);
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] decode(String encoded) {
		return Base64.getUrlDecoder().decode(encoded);
	}

	private static byte[] mac(byte[] key, String data) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
	}

}