package io.github.leohou0430.llmgw.mcp;

/**
 * MCP 工具授权 SPI（Chapter-9 §9.3.3 第三步）。授权按主体、服务器与工具 （及参数）决定是否放行；输入 Schema 校验只保证类型和结构合规，并不证明
 * 业务授权成立。实现必须默认拒绝：没有匹配规则即 DENY——工具可发现 （tools/list 可见）不等于已授权，tools/call 必须独立再次鉴权。
 */
public interface McpAccessPolicy {

	/**
	 * 判定一次 MCP 请求的执行权。{@code toolName} 仅在具名方法下非空 （tools/call 的 params.name；resources/read
	 * 传资源 URI）。
	 */
	McpDecision check(McpAccessQuery query);

	/** 授权查询：主体 × 服务器 × 方法（× 工具/资源）。 */
	record McpAccessQuery(String subjectRef, String tenantId, String projectId, String method, String serverName,
			String toolName, String paramsJson) {
		public McpAccessQuery {
			subjectRef = subjectRef == null ? "" : subjectRef;
			tenantId = tenantId == null ? "" : tenantId;
			projectId = projectId == null ? "" : projectId;
			method = method == null ? "" : method;
			serverName = serverName == null ? "" : serverName;
			toolName = toolName == null ? "" : toolName;
			paramsJson = paramsJson == null ? "" : paramsJson;
		}
	}

	/** 判定结果：放行 / 拒绝（可解释原因）/ 审批挂起（高风险工具）。 */
	record McpDecision(Type type, String reason, String approvalTarget) {

		public enum Type {

			ALLOW, DENY, REQUIRE_APPROVAL

		}

		public static McpDecision allow() {
			return new McpDecision(Type.ALLOW, "", "");
		}

		public static McpDecision deny(String reason) {
			return new McpDecision(Type.DENY, reason == null ? "denied" : reason, "");
		}

		public static McpDecision requireApproval(String target) {
			return new McpDecision(Type.REQUIRE_APPROVAL, "approval required", target);
		}
	}

}
