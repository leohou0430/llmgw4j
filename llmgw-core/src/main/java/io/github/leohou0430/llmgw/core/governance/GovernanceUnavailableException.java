package io.github.leohou0430.llmgw.core.governance;

/**
 * Chapter-9 §9.2.7/§9.6.7：治理依赖（如 Redis）在准入路径上不可用且策略为 fail-closed 时抛出。引擎必须把它映射为 503 +
 * {@code Retry-After}（与容量 语义一致），而不是把基础设施故障伪装成上游 502。每个依赖的分支行为由
 * {@code gateway.redis.failure-policy} 显式定义——不存在未声明的全局放行。
 */
public class GovernanceUnavailableException extends RuntimeException {

	public GovernanceUnavailableException(String dependency, String operation) {
		super("governance dependency unavailable: " + dependency + " failed during " + operation
				+ "; failing closed per redis.failure-policy");
	}

}
