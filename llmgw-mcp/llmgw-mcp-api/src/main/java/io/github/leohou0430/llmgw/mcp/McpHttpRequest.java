package io.github.leohou0430.llmgw.mcp;

import java.util.List;
import java.util.Map;

/**
 * 已由传输层解析的 MCP 网关入站请求。四步准入的第一步「认证」在进入本接口
 * 前已完成：{@code subjectRef}/{@code tenantId}/{@code projectId} 是统一认证层 （API key /
 * JWT）映射出的稳定策略主体，不由协议元数据自述（Chapter-9 §9.3.3：协议说明不是调用者身份证明——逐请求元数据只服务协议校验与转发
 * 两步，不能替代第一、三步的身份和权限判断）。
 *
 * @param httpMethod HTTP 方法（仅接受 POST）
 * @param path 请求路径（约定 /mcp/{server}）
 * @param headers 入站头（多值，键为线上大小写）
 * @param body 原始请求体字节
 */
public record McpHttpRequest(String httpMethod, String path, Map<String, List<String>> headers, byte[] body,
		String subjectRef, String tenantId, String projectId) {

	public McpHttpRequest {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
		body = body == null ? new byte[0] : body;
		subjectRef = subjectRef == null ? "" : subjectRef;
		tenantId = tenantId == null ? "" : tenantId;
		projectId = projectId == null ? "" : projectId;
	}

	/** 大小写不敏感取第一个头值；缺失返回 null。 */
	public String firstHeader(String name) {
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				List<String> values = entry.getValue();
				return values == null || values.isEmpty() ? null : values.get(0);
			}
		}
		return null;
	}
}
