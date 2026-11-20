package io.github.leohou0430.llmgw.conversion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 降级授权（矩阵 §11）：默认空集合，逐项显式授权；清单外的 id 一律 400，不存在默许未知字段的全局开关。 {@link #KNOWN_IDS}
 * 是矩阵清单的代码化常量，不可运行期改写；授权快照本身每请求不可变。 已知 id 校验只在紧凑构造器一处：直接构造的违规路径是组件名 {@code allowed}，头解析归到
 * {@link #HEADER}。
 */
public record DegradationGrant(Set<String> allowed) {

	public static final String HEADER = "X-LLMGW-Allow-Degradation";

	public static final Set<String> KNOWN_IDS = Set.of("thinking.budget_to_effort", "thinking.display_raw",
			"tool_choice.force_to_auto", "response_format.schema_to_object", "tool_call.legacy_function");

	public DegradationGrant {
		allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
		for (String id : allowed) {
			if (!KNOWN_IDS.contains(id)) {
				throw new CapabilityException(CapabilityException.DEGRADATION_NOT_AUTHORIZED, 400, "allowed",
						"未知降级能力 id: " + id);
			}
		}
	}

	public static DegradationGrant empty() {
		return new DegradationGrant(Set.of());
	}

	/** 解析头值：逗号分隔、trim、忽略空段；null/blank → 空授权。 */
	public static DegradationGrant parse(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			return empty();
		}
		Set<String> ids = new HashSet<>();
		for (String segment : headerValue.split(",")) {
			String id = segment.strip();
			if (!id.isEmpty()) {
				ids.add(id);
			}
		}
		try {
			return new DegradationGrant(ids);
		}
		catch (CapabilityException error) {
			throw atHeader(error);
		}
	}

	public boolean allows(String id) {
		return allowed.contains(id);
	}

	/** 头路径的违规指向头名而非 record 组件名；稳定码、状态与原因保持不变。 */
	private static CapabilityException atHeader(CapabilityException error) {
		List<Violation> violations = error.violations()
			.stream()
			.map(violation -> new Violation(violation.code(), HEADER, violation.reason()))
			.toList();
		return new CapabilityException(error.status(), violations);
	}
}