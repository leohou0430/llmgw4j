package io.github.leohou0430.llmgw.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 网关出站响应。错误（400/403/404/502）时 body 为 JSON-RPC 2.0 error 对象；202 表示审批挂起（审批未决不得执行，HTTP
 * 不长挂——Chapter-9 §9.5.4）。 首版返回单次 JSON 交换；上游 SSE 响应被解析出结果帧后以 JSON 返回 （服务端主动流式不在首版声明范围）。
 */
public record McpHttpResponse(int status, Map<String, List<String>> headers, byte[] body) {

	public McpHttpResponse {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
		body = body == null ? new byte[0] : body;
	}

	public static McpHttpResponse of(int status, String contentType, byte[] body) {
		return new McpHttpResponse(status, Map.of("Content-Type", List.of(contentType)), body);
	}
}
