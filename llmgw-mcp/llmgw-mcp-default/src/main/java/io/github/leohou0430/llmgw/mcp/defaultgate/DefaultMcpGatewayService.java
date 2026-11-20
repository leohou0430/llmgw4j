package io.github.leohou0430.llmgw.mcp.defaultgate;

import io.github.leohou0430.llmgw.mcp.McpAccessPolicy;
import io.github.leohou0430.llmgw.mcp.McpGatewayService;
import io.github.leohou0430.llmgw.mcp.McpHttpRequest;
import io.github.leohou0430.llmgw.mcp.McpHttpResponse;
import io.github.leohou0430.llmgw.mcp.McpProtocol;
import io.github.leohou0430.llmgw.mcp.McpServerDef;
import io.github.leohou0430.llmgw.mcp.McpSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 网关默认实现（Chapter-9 §9.3）。四步准入在转发前全部完成：
 *
 * <ol>
 * <li><b>认证</b>——由统一入口完成，{@link McpHttpRequest} 携带稳定主体；
 * 逐请求元数据（{@code params._meta}、镜像头）不构成身份证明（§9.3.3）。</li>
 * <li><b>协议校验</b>——JSON-RPC 结构、Modern 必需元数据（协议版本 + clientCapabilities，允许空对象）、HTTP
 * 镜像头一致性（§9.8.1）； 报文上限与 Origin 校验构成入口边界。</li>
 * <li><b>授权</b>——默认拒绝；{@code tools/list} 按同一授权谓词过滤可见 清单（减少模型误选），{@code tools/call}
 * 独立再次鉴权（§9.3.5）。</li>
 * <li><b>转发</b>——按上游 RPC 重构出口请求：不透传下游 Cookie/会话标识/ 内部路由头/调用方凭证；上游凭证仅来自
 * {@link McpServerDef#credential()}； 镜像头按上游请求重新生成（§9.3.6）。</li>
 * </ol>
 *
 * <p>
 * 桥接：Modern 下游 → Legacy 上游采用<b>单次交换隔离</b>——每次下游交换 内先完成上游 initialize/initialized 握手再发目标
 * RPC，会话标识不跨请求 保留（代价是重复握手，换来网关不长期持有上游协议会话）。Legacy 下游 → Modern 上游的反向路径未声明支持。
 */
public final class DefaultMcpGatewayService implements McpGatewayService {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** Legacy 桥接使用的上游协议基线（initialize 握手代际）。 */
	private static final String LEGACY_BRIDGE_VERSION = "2025-06-18";

	private final McpSettings settings;

	private final McpAccessPolicy policy;

	private final HttpClient http;

	public DefaultMcpGatewayService(McpSettings settings, McpAccessPolicy policy) {
		this.settings = settings;
		this.policy = policy;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	@Override
	public McpHttpResponse handle(McpHttpRequest request) {
		if (!"POST".equalsIgnoreCase(request.httpMethod())) {
			return jsonRpcError(405, -32600, "MCP gateway accepts POST only");
		}
		McpServerDef server = resolveServer(request.path());
		if (server == null) {
			return jsonRpcError(404, -32600, "unknown mcp server route");
		}
		JsonNode message;
		try {
			message = MAPPER.readTree(request.body());
		}
		catch (RuntimeException e) {
			return jsonRpcError(400, -32700, "invalid json: " + e.getMessage());
		}
		McpHttpResponse protocolFailure = validateProtocol(request, message);
		if (protocolFailure != null) {
			return protocolFailure;
		}
		String method = message.path("method").asString("");
		JsonNode params = message.path("params");

		for (String prefix : McpProtocol.DISABLED_METHOD_PREFIXES) {
			if (method.startsWith(prefix)) {
				return jsonRpcError(403, -32600,
						"mcp extension '" + method + "' is disabled by default; enable explicitly");
			}
		}

		// 第三步：授权（默认拒绝；tools/list 过滤与 tools/call 独立鉴权）。
		String toolName = targetedName(method, params);
		McpAccessPolicy.McpDecision decision = policy.check(new McpAccessPolicy.McpAccessQuery(request.subjectRef(),
				request.tenantId(), request.projectId(), method, server.name(), toolName, params.toString()));
		return switch (decision.type()) {
			case DENY -> jsonRpcError(403, -32600, decision.reason());
			case REQUIRE_APPROVAL -> approvalPending(decision.approvalTarget());
			case ALLOW -> forward(server, request, message, method, toolName);
		};
	}

	// ------------------------------------------------------------------
	// 第二步协议校验（认证已在 McpHttpRequest 组装前完成）：
	// ------------------------------------------------------------------

	/** 返回 null = 校验通过；否则为 4xx JSON-RPC error 响应。 */
	private McpHttpResponse validateProtocol(McpHttpRequest request, JsonNode message) {
		String contentType = request.firstHeader("Content-Type");
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
			return jsonRpcError(400, -32600, "Content-Type must include application/json");
		}
		String accept = request.firstHeader("Accept");
		String loweredAccept = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
		if (!loweredAccept.contains("application/json") && !loweredAccept.contains("text/event-stream")) {
			return jsonRpcError(400, -32600, "Accept must include application/json or text/event-stream");
		}
		if (request.body().length > settings.maxBodyBytes()) {
			// 实现额外设定的报文上限（配置来源：mcp settings maxBodyBytes）。
			return jsonRpcError(400, -32600, "mcp body exceeds " + settings.maxBodyBytes() + " bytes");
		}
		String origin = request.firstHeader("Origin");
		if (origin != null && !settings.allowedOrigins().isEmpty() && !settings.allowedOrigins().contains(origin)) {
			return jsonRpcError(403, -32600, "origin not allowed");
		}
		if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asString(""))) {
			return jsonRpcError(400, -32600, "jsonrpc 2.0 object required");
		}
		String method = message.path("method").asString("");
		if (method.isBlank()) {
			return jsonRpcError(400, -32600, "method is required");
		}
		JsonNode params = message.path("params");
		if (!params.isObject()) {
			return jsonRpcError(400, -32602, "params object required");
		}
		JsonNode meta = params.path("_meta");
		if (!meta.isObject()) {
			return jsonRpcError(400, -32602,
					"params._meta required with io.modelcontextprotocol/protocolVersion and clientCapabilities");
		}
		String declaredVersion = meta.path(McpProtocol.META_PROTOCOL_VERSION).asString("");
		if (!McpProtocol.SUPPORTED_VERSIONS.contains(declaredVersion)) {
			return jsonRpcError(400, -32602, "unsupported mcp protocol version '" + declaredVersion + "'; supported: "
					+ McpProtocol.SUPPORTED_VERSIONS);
		}
		if (!meta.has(McpProtocol.META_CLIENT_CAPABILITIES)) {
			return jsonRpcError(400, -32602,
					"params._meta.io.modelcontextprotocol/clientCapabilities required (may be an empty object)");
		}
		// 镜像头：与正文一致；头部可辅助轻量分类与路由，最终执行前必须
		// 验证其与正文一致，避免头部鉴权和正文执行面对不同对象（§9.3.3）。
		String headerVersion = request.firstHeader(McpProtocol.HEADER_PROTOCOL_VERSION);
		if (headerVersion == null || !headerVersion.equals(declaredVersion)) {
			return jsonRpcError(400, -32602, McpProtocol.HEADER_PROTOCOL_VERSION
					+ " header must be present and match params._meta protocol version");
		}
		String headerMethod = request.firstHeader(McpProtocol.HEADER_METHOD);
		if (headerMethod == null || !headerMethod.equals(method)) {
			return jsonRpcError(400, -32602, McpProtocol.HEADER_METHOD + " header must match the JSON-RPC method");
		}
		if (McpProtocol.NAME_MIRROR_METHODS.contains(method)) {
			String expected = targetedName(method, params);
			String headerName = request.firstHeader(McpProtocol.HEADER_NAME);
			if (expected == null || expected.isBlank() || headerName == null || !headerName.equals(expected)) {
				return jsonRpcError(400, -32602,
						McpProtocol.HEADER_NAME + " header must mirror the request's own target parameter");
			}
		}
		// 其余方法不强制要求名称镜像，也不把所有名称字段解释为 params.name。
		return null;
	}

	/** 具名请求的目标参数：tools/call 与 prompts/get 取 params.name，resources/read 取 params.uri。 */
	private static String targetedName(String method, JsonNode params) {
		if ("resources/read".equals(method)) {
			String uri = params.path("uri").asString("");
			return uri.isBlank() ? null : uri;
		}
		if (McpProtocol.NAME_MIRROR_METHODS.contains(method)) {
			String name = params.path("name").asString("");
			return name.isBlank() ? null : name;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// 第四步：转发（Modern 直发 / Legacy 单次交换桥接）：
	// ------------------------------------------------------------------

	private McpHttpResponse forward(McpServerDef server, McpHttpRequest request, JsonNode message, String method,
			String toolName) {
		try {
			if (server.legacy()) {
				return bridgeLegacy(server, request, message, method, toolName);
			}
			McpHttpResponse upstream = exchange(server, MAPPER.writeValueAsBytes(message), method, toolName, null);
			if (upstream.status() != 200) {
				return upstream;
			}
			JsonNode envelope = parseUpstreamBody(upstream.body());
			if ("tools/list".equals(method) && envelope != null && envelope.isObject()) {
				return filterToolList(envelope, request.subjectRef(), request.tenantId(), request.projectId(),
						server.name());
			}
			return McpHttpResponse.of(200, "application/json", upstream.body());
		}
		catch (IOException e) {
			return jsonRpcError(502, -32603, "mcp upstream unreachable: " + e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return jsonRpcError(502, -32603, "mcp upstream interrupted");
		}
	}

	/** tools/list 按授权谓词过滤：可见 = 可调用（tools/call 仍独立再验）。 */
	private McpHttpResponse filterToolList(JsonNode envelope, String subjectRef, String tenantId, String projectId,
			String serverName) {
		JsonNode tools = envelope.path("result").path("tools");
		if (!tools.isArray()) {
			return McpHttpResponse.of(200, "application/json", MAPPER.writeValueAsBytes(envelope));
		}
		ObjectNode filtered = MAPPER.createObjectNode();
		filtered.setAll((ObjectNode) envelope);
		ArrayNode kept = MAPPER.createArrayNode();
		for (JsonNode tool : tools) {
			String name = tool.path("name").asString("");
			McpAccessPolicy.McpDecision visible = policy.check(new McpAccessPolicy.McpAccessQuery(subjectRef, tenantId,
					projectId, "tools/call", serverName, name, ""));
			if (visible.type() == McpAccessPolicy.McpDecision.Type.ALLOW) {
				kept.add(tool);
			}
		}
		ObjectNode resultNode = filtered.withObject("result");
		resultNode.set("tools", kept);
		return McpHttpResponse.of(200, "application/json", MAPPER.writeValueAsBytes(filtered));
	}

	/**
	 * Legacy 桥接（单次交换隔离）：initialize → notifications/initialized → 目标
	 * RPC；上游会话标识仅在本次交换内使用，结束后丢弃。
	 */
	private McpHttpResponse bridgeLegacy(McpServerDef server, McpHttpRequest request, JsonNode message, String method,
			String toolName) {
		try {
			ObjectNode initialize = MAPPER.createObjectNode();
			initialize.put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
			ObjectNode initParams = initialize.putObject("params");
			initParams.put("protocolVersion", LEGACY_BRIDGE_VERSION);
			initParams.putObject("capabilities");
			initParams.putObject("clientInfo").put("name", McpProtocol.BRIDGE_CLIENT_NAME).put("version", "0.5.0");
			McpHttpResponse handshake = exchange(server, MAPPER.writeValueAsBytes(initialize), "initialize", null,
					null);
			if (handshake.status() != 200) {
				return jsonRpcError(502, -32603, "legacy upstream initialize failed");
			}
			String session = firstValue(handshake.headers(), McpProtocol.HEADER_LEGACY_SESSION);

			ObjectNode initialized = MAPPER.createObjectNode();
			initialized.put("jsonrpc", "2.0").put("method", "notifications/initialized");
			exchange(server, MAPPER.writeValueAsBytes(initialized), "notifications/initialized", null, session);

			McpHttpResponse upstream = exchange(server, MAPPER.writeValueAsBytes(message), method, toolName, session);
			if (upstream.status() != 200) {
				return upstream;
			}
			JsonNode envelope = parseUpstreamBody(upstream.body());
			if ("tools/list".equals(method) && envelope != null && envelope.isObject()) {
				return filterToolList(envelope, request.subjectRef(), request.tenantId(), request.projectId(),
						server.name());
			}
			return McpHttpResponse.of(200, "application/json", upstream.body());
		}
		catch (IOException e) {
			return jsonRpcError(502, -32603, "legacy upstream unreachable: " + e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return jsonRpcError(502, -32603, "legacy upstream interrupted");
		}
	}

	/** 重构出口请求：仅带网关生成的头与上游凭证，绝不透传下游报文细节。 */
	private McpHttpResponse exchange(McpServerDef server, byte[] body, String method, String toolName,
			String legacySession) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.url()))
			.timeout(Duration.ofMillis(settings.upstreamTimeoutMs()))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header(McpProtocol.HEADER_PROTOCOL_VERSION,
					server.legacy() ? LEGACY_BRIDGE_VERSION : McpProtocol.PROTOCOL_VERSION)
			.header(McpProtocol.HEADER_METHOD, method)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (toolName != null && !toolName.isBlank() && McpProtocol.NAME_MIRROR_METHODS.contains(method)) {
			builder.header(McpProtocol.HEADER_NAME, toolName);
		}
		if (!server.credential().isBlank()) {
			builder.header("Authorization", "Bearer " + server.credential());
		}
		if (legacySession != null && !legacySession.isBlank()) {
			builder.header(McpProtocol.HEADER_LEGACY_SESSION, legacySession);
		}
		HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() >= 400) {
			return jsonRpcError(502, -32603, "mcp upstream error: HTTP " + response.statusCode());
		}
		// 出站响应只保留协议必需头（Content-Type 与 Legacy 会话标识）；
		// 上游的 Cookie/内部头不下发到下游。
		var headers = new java.util.LinkedHashMap<String, List<String>>();
		headers.put("Content-Type", List.of("application/json"));
		String session = response.headers().firstValue(McpProtocol.HEADER_LEGACY_SESSION).orElse(null);
		if (session != null) {
			headers.put(McpProtocol.HEADER_LEGACY_SESSION, List.of(session));
		}
		return new McpHttpResponse(200, headers, extractResult(response));
	}

	/** 上游响应提取：application/json 直取；SSE 取最后一个 data: 帧。 */
	private static byte[] extractResult(HttpResponse<byte[]> response) {
		String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
		if (contentType.contains("text/event-stream")) {
			String text = new String(response.body(), StandardCharsets.UTF_8);
			String lastData = null;
			for (String line : text.split("\n")) {
				if (line.startsWith("data:")) {
					lastData = line.substring(5).trim();
				}
			}
			return lastData == null ? new byte[0] : lastData.getBytes(StandardCharsets.UTF_8);
		}
		return response.body();
	}

	private JsonNode parseUpstreamBody(byte[] body) {
		try {
			return MAPPER.readTree(body);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private McpHttpResponse approvalPending(String approvalTarget) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("approvalRequired", true)
			.put("approvalTarget", approvalTarget == null ? "" : approvalTarget)
			.put("note", "approval pending; execution is blocked until revalidated (approval != forwarding)");
		return McpHttpResponse.of(202, "application/json", MAPPER.writeValueAsBytes(body));
	}

	private McpServerDef resolveServer(String path) {
		String serverName = "";
		if (path != null && path.startsWith("/mcp/")) {
			serverName = path.substring("/mcp/".length());
			int slash = serverName.indexOf('/');
			if (slash >= 0) {
				serverName = serverName.substring(0, slash);
			}
		}
		for (McpServerDef def : settings.servers()) {
			if (def.name().equals(serverName)) {
				return def;
			}
		}
		return null;
	}

	private static McpHttpResponse jsonRpcError(int status, int code, String message) {
		ObjectNode error = MAPPER.createObjectNode();
		error.put("jsonrpc", "2.0");
		ObjectNode body = error.putObject("error");
		body.put("code", code).put("message", message == null ? "" : message);
		return McpHttpResponse.of(status, "application/json", MAPPER.writeValueAsBytes(error));
	}

	private static String firstValue(Map<String, List<String>> headers, String name) {
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
				return entry.getValue().getFirst();
			}
		}
		return null;
	}

}
