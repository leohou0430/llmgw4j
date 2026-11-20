package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/**
 * 每请求的入口能力上下文：租户/项目归属 + 降级授权留痕 + 思考续轮信封。 {@code projectId}
 * 是续轮信封的项目绑定输入（T1.8）；{@code envelope} 由调用方按请求传入（密钥归 ExtensionContext，codec
 * 无状态、不持有密钥）；{@code audit} 实例不得跨请求共享。
 */
public record CapabilityContext(String tenantId, String projectId, DegradationAudit audit,
		ContinuationEnvelope envelope) {

	public CapabilityContext {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(audit, "audit");
		Objects.requireNonNull(envelope, "envelope");
		if (tenantId.isBlank()) {
			throw new IllegalArgumentException("tenantId must not be blank");
		}
		if (projectId.isBlank()) {
			throw new IllegalArgumentException("projectId must not be blank");
		}
	}
}