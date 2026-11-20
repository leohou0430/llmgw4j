package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.mcp.McpGatewayService;
import io.github.leohou0430.llmgw.mcp.McpHttpRequest;
import io.github.leohou0430.llmgw.mcp.McpHttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 网关 HTTP 入口（Chapter-9 §9.3，ADR-0010）。认证由本控制器完成—— 复用数据面 API key 注册表把凭据映射为稳定策略主体（API
 * key 即主体， tenant/project 元数据即财务归属）；协议校验、授权与转发全部委托
 * {@link McpGatewayService}（协议说明不是调用者身份证明）。每次调用写审计。
 *
 * <p>
 * 未启用（{@code gateway.mcp.enabled=false}）时端点 404；仅注册表 key 支持 MCP 入口（JWT 虚拟 key 后续扩展）。
 * </p>
 */
@RestController
public class McpGatewayController {

	private final ObjectProvider<McpGatewayService> gateway;

	private final ApiKeyRegistry apiKeys;

	private final AuditSink audit;

	private final GatewayProperties properties;

	public McpGatewayController(ObjectProvider<McpGatewayService> gateway, ApiKeyRegistry apiKeys, AuditSink audit,
			GatewayProperties properties) {
		this.gateway = gateway;
		this.apiKeys = apiKeys;
		this.audit = audit;
		this.properties = properties;
	}

	@PostMapping("/mcp/{server}")
	public ResponseEntity<byte[]> handle(@PathVariable("server") String server, HttpServletRequest request) {
		McpGatewayService service = gateway.getIfAvailable();
		if (service == null) {
			return jsonRpc(404, "mcp gateway disabled");
		}
		// 第一步：认证（稳定主体映射；协议元数据不参与身份判断）。
		String authorization = request.getHeader("Authorization");
		String token = bearer(authorization);
		ApiKeyRegistry.ApiKey key = token.isEmpty() ? null : apiKeys.find(token).orElse(null);
		if (key == null) {
			audit.emit(new AuditSink.AuditEvent(Instant.now(), "", "mcp.denied",
					Map.of("reason", "invalid key", "server", server)));
			return jsonRpc(401, "invalid api key");
		}
		String tenant = key.metadata() == null ? "" : key.metadata().getOrDefault("llmgw.tenantId", "");
		String project = key.metadata() == null ? "" : key.metadata().getOrDefault("llmgw.projectId", "");
		if (tenant.isBlank() || project.isBlank()) {
			// 与数据面主体映射同一合同：无归属主体的请求不进转发面。
			audit.emit(new AuditSink.AuditEvent(Instant.now(), key.id(), "mcp.denied",
					Map.of("reason", "unattributed subject", "server", server)));
			return jsonRpc(401, "unattributed subject: key metadata must carry tenant and project");
		}

		// 报文按 MCP 上限有界读取（超限由服务层以 JSON-RPC error 拒绝）。
		long cap = properties.getMcp().isEnabled() ? properties.getMcp().getMaxBodyBytes() : 1024 * 1024;
		byte[] body;
		try {
			body = request.getInputStream().readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 1L));
		}
		catch (java.io.IOException e) {
			return jsonRpc(400, "unreadable body: " + e.getMessage());
		}
		Map<String, List<String>> headers = new LinkedHashMap<>();
		var names = request.getHeaderNames();
		if (names != null) {
			for (String name : java.util.Collections.list(names)) {
				headers.put(name, List.of(request.getHeader(name)));
			}
		}
		McpHttpResponse response = service.handle(
				new McpHttpRequest(request.getMethod(), "/mcp/" + server, headers, body, key.id(), tenant, project));
		audit.emit(new AuditSink.AuditEvent(Instant.now(), key.id(), "mcp.request",
				Map.of("server", server, "status", response.status(), "tenantId", tenant, "projectId", project)));
		return ResponseEntity.status(response.status()).headers(httpHeaders(response)).body(response.body());
	}

	private static org.springframework.http.HttpHeaders httpHeaders(McpHttpResponse response) {
		org.springframework.http.HttpHeaders out = new org.springframework.http.HttpHeaders();
		response.headers().forEach(out::put);
		return out;
	}

	private static ResponseEntity<byte[]> jsonRpc(int status, String message) {
		String body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"" + message.replace("\"", "'")
				+ "\"}}";
		return ResponseEntity.status(status)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.body(body.getBytes(StandardCharsets.UTF_8));
	}

	private static String bearer(String header) {
		if (header == null) {
			return "";
		}
		String value = header.trim();
		if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return value.substring(7).trim();
		}
		return value;
	}

}
