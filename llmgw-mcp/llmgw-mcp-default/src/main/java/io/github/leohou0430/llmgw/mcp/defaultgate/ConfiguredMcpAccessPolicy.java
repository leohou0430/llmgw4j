package io.github.leohou0430.llmgw.mcp.defaultgate;

import io.github.leohou0430.llmgw.mcp.McpAccessPolicy;
import io.github.leohou0430.llmgw.mcp.McpRule;
import io.github.leohou0430.llmgw.mcp.McpSettings;

/**
 * 配置驱动的 MCP 授权策略：按「主体 × 服务器 × 工具」规则顺序匹配，首条 命中即生效；无命中一律 DENY（Chapter-9 §9.3.5 默认拒绝）。未认证主体
 * （空 subjectRef）不参与任何匹配——直接拒绝。规则顺序即优先级，管理面 发布时负责冲突检查。
 */
public final class ConfiguredMcpAccessPolicy implements McpAccessPolicy {

	private final McpSettings settings;

	public ConfiguredMcpAccessPolicy(McpSettings settings) {
		this.settings = settings;
	}

	@Override
	public McpDecision check(McpAccessQuery query) {
		if (query.subjectRef().isBlank()) {
			return McpDecision.deny("unauthenticated subject");
		}
		for (McpRule rule : settings.rules()) {
			if (matches(rule.subjectPattern(), query.subjectRef()) && matches(rule.server(), query.serverName())
					&& matches(rule.tool(), query.toolName())) {
				return switch (rule.action()) {
					case "allow" -> McpDecision.allow();
					case "deny" -> McpDecision
						.deny("denied by rule " + rule.subjectPattern() + "/" + rule.server() + "/" + rule.tool());
					default -> McpDecision.requireApproval(query.serverName() + "/"
							+ (query.toolName().isEmpty() ? query.method() : query.toolName()));
				};
			}
		}
		return McpDecision.deny("no matching rule (default deny)");
	}

	/** {@code *} = 全部；{@code prefix*} = 前缀；否则精确匹配。 */
	static boolean matches(String pattern, String value) {
		if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
			return true;
		}
		if (pattern.endsWith("*")) {
			return value.startsWith(pattern.substring(0, pattern.length() - 1));
		}
		return pattern.equals(value);
	}

}
