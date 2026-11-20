package io.github.leohou0430.llmgw.recording.local;

import java.util.regex.Pattern;

/**
 * 记录消毒器（任务5验收）：认证字段、带令牌 URL、data URI 与跨块 Base64、 二进制片段一律不落盘；合法工具文本（普通参数与命令输出）可记录。输出为
 * 纯文本——转发字节完全一致（消毒只作用于记录旁路，不触碰数据面）。
 */
public final class RecordingSanitizer {

	/** JSON 认证字段（值整体掩码）：authorization/api-key/x-api-key/api_key 等。 */
	private static final Pattern AUTH_JSON_FIELDS = Pattern
		.compile("(?i)(\"(?:authorization|x-api-key|api[-_]?key|api-key-header|client[-_]?secret"
				+ "|access[-_]?token|refresh[-_]?token)\"\\s*:\\s*\")([^\"]*)(\")");

	/** 请求头行（Authorization: Bearer xxx / x-api-key: ...）。 */
	private static final Pattern AUTH_HEADER_LINE = Pattern
		.compile("(?im)^\\s*(authorization|x-api-key|api-key|proxy-authorization)\\s*:\\s*(.+)$");

	/** URL 查询令牌（?token=/&key=/&access_token= 等，直到 & 或结尾）。 */
	private static final Pattern TOKEN_URL = Pattern
		.compile("(?i)([?&](?:token|access[-_]?token|api[-_]?key|apikey|key|sig|signature)=)"
				+ "([^&\\s\"']+)([^&\\s\"']*)");

	/** data URI（Base64 主体）：整段掩码（不含空白——避免吞掉合法尾文）。 */
	private static final Pattern DATA_URI = Pattern.compile("(?i)data:[\\w.+-]+/[\\w.+-]+;base64,[A-Za-z0-9+/=]{8,}");

	/** 裸 Base64 长块（≥64 个合法字符且包含换行块形态——跨块累积体）。 */
	private static final Pattern BARE_BASE64_BLOCK = Pattern
		.compile("(?m)(?:[A-Za-z0-9+/]{60,}\\n)+[A-Za-z0-9+/]{20,}={0,2}");

	/** 二进制片段：控制字符（除 \t\n\r）密度超过阈值即按二进制省略。 */
	private static final double BINARY_CONTROL_RATIO = 0.15;

	private RecordingSanitizer() {
	}

	/** 消毒一条待记录载荷：掩码凭证与令牌，二进制整段省略。 */
	public static String sanitize(String payload) {
		if (payload == null || payload.isEmpty()) {
			return payload == null ? "" : payload;
		}
		String out = payload;
		out = DATA_URI.matcher(out).replaceAll(m -> "[data-uri " + estimateBytes(m.group()) + " bytes omitted]");
		out = BARE_BASE64_BLOCK.matcher(out).replaceAll(m -> "[base64 " + estimateBytes(m.group()) + " bytes omitted]");
		out = AUTH_JSON_FIELDS.matcher(out).replaceAll("$1***$3");
		out = AUTH_HEADER_LINE.matcher(out).replaceAll("$1: ***");
		out = TOKEN_URL.matcher(out).replaceAll("$1***$3");
		if (looksBinary(out)) {
			return "[binary " + estimateBytes(payload) + " bytes omitted]";
		}
		return out;
	}

	private static boolean looksBinary(String text) {
		if (text.isEmpty()) {
			return false;
		}
		int control = 0;
		int sample = Math.min(text.length(), 4096);
		for (int i = 0; i < sample; i++) {
			char c = text.charAt(i);
			if (c == '\t' || c == '\n' || c == '\r') {
				continue;
			}
			if (c < 0x20 || c == 0xFFFD) {
				control++;
			}
		}
		return (double) control / sample > BINARY_CONTROL_RATIO;
	}

	private static int estimateBytes(String s) {
		return s.length();
	}

}
