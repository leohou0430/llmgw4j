package io.github.leohou0430.llmgw.conversion.gemini;

import java.util.Objects;
import java.util.Set;

/**
 * Gemini 路径解析（任务7安全合同）：仅允许已配置的上游 API 版本/操作，不转发 任意路径。URL 形态
 * {@code /v1beta/models/{model}:generateContent}；model 只 接受安全字符（注入路径/跨段分隔被拒绝）。
 */
public final class GeminiPaths {

	/** 已配置的上游操作白名单（不转发任意路径）。 */
	public static final Set<String> OPERATIONS = Set.of("generateContent", "streamGenerateContent", "countTokens");

	/** 已配置的上游 API 版本前缀。 */
	public static final String API_PREFIX = "/v1beta/models/";

	private GeminiPaths() {
	}

	/** 解析结果：路径 model、操作名、是否流式。 */
	public record Parsed(String model, String operation, boolean streaming) {
	}

	/**
	 * 解析 Gemini 生成路径。非法路径/未知操作/不安全 model 抛 {@link IllegalArgumentException}（调用方
	 * 400，不触碰上游）。
	 */
	public static Parsed parse(String path) {
		Objects.requireNonNull(path, "path");
		String trimmed = path.split("\\?")[0];
		if (!trimmed.startsWith(API_PREFIX)) {
			throw new IllegalArgumentException("only " + API_PREFIX + "{model}:{operation} is allowed: " + path);
		}
		String rest = trimmed.substring(API_PREFIX.length());
		int colon = rest.lastIndexOf(':');
		if (colon <= 0) {
			throw new IllegalArgumentException("missing :operation suffix: " + path);
		}
		String model = rest.substring(0, colon);
		String operation = rest.substring(colon + 1);
		if (!OPERATIONS.contains(operation)) {
			throw new IllegalArgumentException("operation not allowed: " + operation);
		}
		validateModel(model, path);
		return new Parsed(model, operation, "streamGenerateContent".equals(operation));
	}

	/** model 注入防护：只允许字母数字与 . _ -，禁路径分隔与相对段。 */
	private static void validateModel(String model, String path) {
		if (model.isEmpty() || model.length() > 200) {
			throw new IllegalArgumentException("invalid model in path: " + path);
		}
		for (int i = 0; i < model.length(); i++) {
			char c = model.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_'
					|| c == '-')) {
				throw new IllegalArgumentException("model path injection rejected: " + path);
			}
		}
		if (model.contains("..")) {
			throw new IllegalArgumentException("model path injection rejected: " + path);
		}
	}

}
