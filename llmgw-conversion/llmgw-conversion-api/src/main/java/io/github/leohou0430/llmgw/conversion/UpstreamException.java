package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/**
 * 上游异常终止/非法载荷/不完整响应（矩阵 §10.1 upstream_error → 502）；错误 JSON/SSE 形状由入口 codec 渲染。
 * {@code cause} 保留底层异常栈（解析/IO 失败不得吞栈）；{@code fieldPath} 指向违规的上游字段路径，可为 null（整体载荷失败）。
 */
public class UpstreamException extends RuntimeException {

	public static final String UPSTREAM_ERROR = "upstream_error";

	private final String fieldPath;

	public UpstreamException(String reason) {
		this(reason, null, null);
	}

	public UpstreamException(String reason, Throwable cause) {
		this(reason, null, cause);
	}

	public UpstreamException(String reason, String fieldPath, Throwable cause) {
		super(Objects.requireNonNull(reason, "reason"), cause);
		this.fieldPath = fieldPath;
	}

	public String code() {
		return UPSTREAM_ERROR;
	}

	public int status() {
		return 502;
	}

	/** 违规上游字段路径；无法定位到具体字段时为 null。 */
	public String fieldPath() {
		return fieldPath;
	}

}