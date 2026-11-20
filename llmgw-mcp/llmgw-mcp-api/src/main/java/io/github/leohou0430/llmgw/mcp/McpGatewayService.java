package io.github.leohou0430.llmgw.mcp;

/**
 * MCP 网关服务（Chapter-9 §9.3）。把协议代理、资源目录信息、授权与实际 执行结果分开治理：本接口承担「协议校验 → 授权 → 转发」三步（认证已由
 * 统一入口完成并以稳定主体传入）。传输无关：HTTP 框架只出现在 app/引擎层， 本接口的输入输出是已解析的请求与响应。
 */
public interface McpGatewayService {

	/**
	 * 处理一次 MCP 调用。返回 200（结果 JSON）、202（审批挂起）、 400/403/404/502（JSON-RPC error）。
	 */
	McpHttpResponse handle(McpHttpRequest request);

}
