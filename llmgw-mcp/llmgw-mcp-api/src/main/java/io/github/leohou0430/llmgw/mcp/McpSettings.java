package io.github.leohou0430.llmgw.mcp;

import java.util.List;

/**
 * MCP 网关装配配置。网关的能力范围必须显式声明（§9.3.2/§9.3.6）：支持的 版本集合见
 * {@link McpProtocol#SUPPORTED_VERSIONS}；订阅与 Tasks 扩展默认 关闭；桥接方向仅 Modern 下游 → Legacy 上游。
 *
 * @param servers 已声明的上游服务器与凭证（出口白名单）
 * @param rules 授权规则（默认拒绝语义）
 * @param maxBodyBytes 入站报文上限（实现额外设定值，配置来源须文档声明）
 * @param allowedOrigins 允许的 Origin（空 = 不校验 Origin）
 * @param upstreamTimeoutMs 上游单次交换超时
 */
public record McpSettings(List<McpServerDef> servers, List<McpRule> rules, long maxBodyBytes,
		List<String> allowedOrigins, long upstreamTimeoutMs) {

	public McpSettings {
		servers = servers == null ? List.of() : List.copyOf(servers);
		rules = rules == null ? List.of() : List.copyOf(rules);
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		maxBodyBytes = maxBodyBytes <= 0 ? 1024 * 1024 : maxBodyBytes;
		upstreamTimeoutMs = upstreamTimeoutMs <= 0 ? 30_000 : upstreamTimeoutMs;
	}
}
