package io.github.leohou0430.llmgw.conversion;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 能力准入失败：HTTP 状态 + 不可变违规清单。 status 必须是 4xx/5xx，违规清单非空且每条三字段有值，全部在构造期校验（消息里不会拼出 null）。
 * 这里只承载合同，错误 JSON / SSE 形状由入口 codec 渲染（矩阵 §10.2）。 {@code cause} 保留底层异常栈（解析/IO
 * 失败不得吞栈），但绝不拼进面向客户端的消息。
 */
public class CapabilityException extends RuntimeException {

	public static final String UNKNOWN_FIELD = "capability_unknown_field";

	public static final String NOT_SUPPORTED = "capability_not_supported";

	public static final String DEGRADATION_NOT_AUTHORIZED = "capability_degradation_not_authorized";

	public static final String INVALID_VALUE = "capability_invalid_value";

	public static final String CONTINUATION_INVALID = "capability_continuation_invalid";

	private final int status;

	private final List<Violation> violations;

	public CapabilityException(int status, List<Violation> violations) {
		this(status, violations, null);
	}

	public CapabilityException(int status, List<Violation> violations, Throwable cause) {
		super(validatedMessage(status, violations), cause);
		this.status = status;
		this.violations = List.copyOf(violations);
	}

	public CapabilityException(String code, int status, String fieldPath, String reason) {
		this(code, status, fieldPath, reason, null);
	}

	public CapabilityException(String code, int status, String fieldPath, String reason, Throwable cause) {
		this(status, List.of(new Violation(code, fieldPath, reason)), cause);
	}

	public int status() {
		return status;
	}

	public List<Violation> violations() {
		return violations;
	}

	/** 构造期校验必须先于 super()：status/违规清单非法时抛 IllegalArgumentException，而不是 NPE。 */
	private static String validatedMessage(int status, List<Violation> violations) {
		if (status < 400 || status > 599) {
			throw new IllegalArgumentException("status must be an HTTP error status in [400,599]: " + status);
		}
		if (violations == null || violations.isEmpty()) {
			throw new IllegalArgumentException("CapabilityException requires at least one violation");
		}
		return message(violations);
	}

	private static String message(List<Violation> violations) {
		return violations.stream()
			.map(violation -> violation.code() + " " + violation.fieldPath() + ": " + violation.reason())
			.collect(Collectors.joining("; "));
	}

}