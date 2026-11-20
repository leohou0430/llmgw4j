package io.github.leohou0430.llmgw.mcp;

/**
 * 已声明的 MCP 上游服务器路由。出口目标只能来自此配置——模板不承载可变 主机/路径/出站目标，请求参数不能指定上游（无 SSRF 面，Chapter-9 §9.3.7）。
 * 凭证是受控秘密的引用值，由凭证托管设施在装配时注入，绝不透传调用方 Authorization，也不下发到客户端配置或日志（§9.3.5）。
 *
 * @param name 服务器逻辑名（路由 /mcp/{name}）
 * @param url 上游端点（固定声明）
 * @param legacy true = Legacy（initialize 握手）上游，走 Modern→Legacy 桥接； false =
 * Modern（2026-07-28）上游
 * @param credential 上游凭证引用（Bearer 注入；空 = 匿名上游）
 */
public record McpServerDef(String name, String url, boolean legacy, String credential) {
	public McpServerDef {
		name = name == null ? "" : name.trim();
		url = url == null ? "" : url.trim();
		credential = credential == null ? "" : credential;
		if (name.isEmpty()) {
			throw new IllegalArgumentException("mcp server name is required");
		}
		if (url.isEmpty()) {
			throw new IllegalArgumentException("mcp server url is required for '" + name + "'");
		}
	}
}
