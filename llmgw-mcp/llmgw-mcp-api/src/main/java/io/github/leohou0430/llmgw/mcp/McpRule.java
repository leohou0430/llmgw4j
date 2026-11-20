package io.github.leohou0430.llmgw.mcp;

/**
 * 单条 MCP 授权规则。匹配维度：主体模式 × 服务器 × 工具/资源；未命中的 一律默认拒绝。{@code * } 通配任意值；前缀通配写作
 * {@code prefix*}。
 *
 * @param subjectPattern 主体模式（精确、{@code prefix*} 或 {@code *}）
 * @param server 服务器名（{@code *} = 全部）
 * @param tool 工具/资源名（{@code *} = 全部）
 * @param action allow | deny | require-approval
 */
public record McpRule(String subjectPattern, String server, String tool, String action) {

	public McpRule {
		subjectPattern = subjectPattern == null ? "" : subjectPattern.trim();
		server = server == null ? "" : server.trim();
		tool = tool == null ? "" : tool.trim();
		action = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
		if (!action.equals("allow") && !action.equals("deny") && !action.equals("require-approval")) {
			throw new IllegalArgumentException("mcp rule action must be allow, deny or require-approval: " + action);
		}
	}
}
