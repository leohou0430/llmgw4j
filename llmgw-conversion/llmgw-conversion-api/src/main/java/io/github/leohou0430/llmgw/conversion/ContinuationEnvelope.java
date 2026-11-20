package io.github.leohou0430.llmgw.conversion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 网关命名空间「思考续轮信封」（矩阵 §7.3）：{@code llmgw1:<base64url(payload)>.<base64url(HMAC-SHA256)>}。
 * 只承载完整性 + 项目绑定 + 推理文本摘要，明文推理仍走各入口明文字段；带显式前缀，不冒充 Anthropic/OpenAI 供应商签名。
 * 密钥由调用方（ExtensionContext）持有，本类不做任何静态全局状态。
 */
public final class ContinuationEnvelope {

	private static final String PREFIX = "llmgw1:";

	private static final String MAC_ALGORITHM = "HmacSHA256";

	private static final String FIELD_PATH = "continuation";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final byte[] key;

	public ContinuationEnvelope(byte[] key) {
		if (key == null || key.length == 0) {
			throw new IllegalArgumentException("continuation key must not be null or empty");
		}
		this.key = key.clone();
	}

	public String mint(String projectId, String itemId, String reasoningText) {
		if (projectId == null || itemId == null || reasoningText == null) {
			throw new IllegalArgumentException("projectId/itemId/reasoningText must not be null");
		}
		ObjectNode payload = JSON.createObjectNode();
		payload.put("projectId", projectId);
		payload.put("itemId", itemId);
		payload.put("reasoningSha256", sha256Hex(reasoningText));
		String encodedPayload = encode(JSON.writeValueAsBytes(payload));
		return PREFIX + encodedPayload + "." + encode(mac(PREFIX + encodedPayload));
	}

	public void verify(String token, String projectId, String reasoningText) {
		try {
			validate(token, projectId, reasoningText);
		}
		catch (CapabilityException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// 畸形输入统一落 400 稳定码，解析/编码异常不得逃逸成 500，也不得把栈拼进客户端消息
			throw invalid("续轮信封不合法", e);
		}
	}

	private void validate(String token, String projectId, String reasoningText) {
		if (token == null || !token.startsWith(PREFIX)) {
			throw invalid("续轮信封缺失或命名空间不符", null);
		}
		String body = token.substring(PREFIX.length());
		int separator = body.indexOf('.');
		if (separator <= 0 || separator != body.lastIndexOf('.') || separator == body.length() - 1) {
			throw invalid("续轮信封格式不合法", null);
		}
		String encodedPayload = body.substring(0, separator);
		byte[] providedMac = Base64.getUrlDecoder().decode(body.substring(separator + 1));
		byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
		// 先验完整性再解析：未认证的字节不交给 JSON 解析器；常量时间比较避免按字节提前返回泄露 MAC
		if (!MessageDigest.isEqual(mac(PREFIX + encodedPayload), providedMac)) {
			throw invalid("续轮信封完整性校验失败", null);
		}
		JsonNode payload = JSON.readTree(payloadBytes);
		if (payload == null || !payload.isObject()) {
			throw invalid("续轮信封内容不合法", null);
		}
		JsonNode boundProject = payload.get("projectId");
		JsonNode itemId = payload.get("itemId");
		JsonNode reasoningDigest = payload.get("reasoningSha256");
		if (boundProject == null || !boundProject.isString() || itemId == null || !itemId.isString()
				|| reasoningDigest == null || !reasoningDigest.isString()) {
			throw invalid("续轮信封字段缺失或类型不合法", null);
		}
		if (projectId == null || !MessageDigest.isEqual(boundProject.asString().getBytes(StandardCharsets.UTF_8),
				projectId.getBytes(StandardCharsets.UTF_8))) {
			throw invalid("续轮信封不属于当前项目", null);
		}
		if (reasoningText == null || !MessageDigest.isEqual(reasoningDigest.asString().getBytes(StandardCharsets.UTF_8),
				sha256Hex(reasoningText).getBytes(StandardCharsets.UTF_8))) {
			throw invalid("推理文本与续轮信封摘要不一致", null);
		}
	}

	private byte[] mac(String data) {
		try {
			Mac mac = Mac.getInstance(MAC_ALGORITHM);
			mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			// HmacSHA256 是 JDK 必备算法且密钥已在构造期校验，这里只可能是环境故障，与客户端输入无关
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	private static String sha256Hex(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static CapabilityException invalid(String reason, Throwable cause) {
		return new CapabilityException(CapabilityException.CONTINUATION_INVALID, 400, FIELD_PATH, reason, cause);
	}

}