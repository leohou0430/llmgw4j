package io.github.leohou0430.llmgw.mcp.defaultgate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.mcp.McpAccessPolicy;
import io.github.leohou0430.llmgw.mcp.McpGatewayService;
import io.github.leohou0430.llmgw.mcp.McpHttpRequest;
import io.github.leohou0430.llmgw.mcp.McpHttpResponse;
import io.github.leohou0430.llmgw.mcp.McpProtocol;
import io.github.leohou0430.llmgw.mcp.McpRule;
import io.github.leohou0430.llmgw.mcp.McpServerDef;
import io.github.leohou0430.llmgw.mcp.McpSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chapter-9 §9.3/§9.8.1 网关合同：镜像矩阵、默认拒绝、tools/list 过滤与 tools/call
 * 独立鉴权、出口请求重构（不透传下游凭证）、Legacy 单次交换 隔离桥接、审批挂起、报文与 Origin 边界、SSE 结果提取。
 */
class DefaultMcpGatewayServiceTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** 假上游收到的一次请求：方法、头、正文。 */
	record Received(String method, Map<String, List<String>> headers, String body) {
		String firstHeader(String name) {
			return headers.entrySet()
				.stream()
				.filter(e -> e.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.filter(v -> !v.isEmpty())
				.map(List::getFirst)
				.findFirst()
				.orElse(null);
		}
	}

	/** 按上游收到的 JSON-RPC method 决定响应的假服务器。 */
	static final class FakeUpstream {

		final HttpServer server;

		final List<Received> received = new CopyOnWriteArrayList<>();

		String targetResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";

		String targetContentType = "application/json";

		FakeUpstream(boolean legacySession) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", (HttpExchange exchange) -> {
				byte[] body = exchange.getRequestBody().readAllBytes();
				received.add(new Received(exchange.getRequestMethod(), exchange.getRequestHeaders(),
						new String(body, StandardCharsets.UTF_8)));
				JsonNode request = MAPPER.readTree(body);
				String method = request.path("method").asString("");
				int status;
				byte[] payload;
				Map<String, List<String>> extraHeaders = Map.of();
				if ("initialize".equals(method)) {
					status = 200;
					payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\"}}}"
						.getBytes(StandardCharsets.UTF_8);
					if (legacySession) {
						extraHeaders = Map.of("Mcp-Session-Id", List.of("sess-legacy-9"));
					}
				}
				else if (method.startsWith("notifications/")) {
					status = 202;
					payload = new byte[0];
				}
				else {
					status = 200;
					payload = targetResponse.getBytes(StandardCharsets.UTF_8);
				}
				extraHeaders.forEach((k, v) -> exchange.getResponseHeaders().put(k, new ArrayList<>(v)));
				exchange.getResponseHeaders().set("Content-Type", targetContentType);
				exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
				if (payload.length > 0) {
					exchange.getResponseBody().write(payload);
				}
				exchange.close();
			});
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
		}

		void close() {
			server.stop(0);
		}

	}

	private final List<FakeUpstream> upstreams = new ArrayList<>();

	private FakeUpstream modernUpstream() throws IOException {
		FakeUpstream upstream = new FakeUpstream(false);
		upstreams.add(upstream);
		return upstream;
	}

	private FakeUpstream legacyUpstream() throws IOException {
		FakeUpstream upstream = new FakeUpstream(true);
		upstreams.add(upstream);
		return upstream;
	}

	@AfterEach
	void tearDown() {
		upstreams.forEach(FakeUpstream::close);
	}

	// ------------------------------------------------------------------
	// 请求构造：
	// ------------------------------------------------------------------

	private static McpSettings settings(FakeUpstream upstream, boolean legacy, String credential, List<McpRule> rules,
			List<String> origins, long maxBodyBytes) {
		return new McpSettings(List.of(new McpServerDef("tools", upstream.url(), legacy, credential)), rules,
				maxBodyBytes, origins, 5000);
	}

	private static McpGatewayService service(McpSettings settings) {
		return new DefaultMcpGatewayService(settings, new ConfiguredMcpAccessPolicy(settings));
	}

	private static McpHttpRequest post(String path, Map<String, List<String>> headers, String body) {
		return new McpHttpRequest("POST", path, headers, body.getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1",
				"project-1");
	}

	private static String toolsCallBody(String name) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{" + "\"_meta\":{" + "\""
				+ McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\"," + "\"" + McpProtocol.META_CLIENT_CAPABILITIES
				+ "\":{}}," + "\"name\":\"" + name + "\",\"arguments\":{}}}";
	}

	private static Map<String, List<String>> mirrorHeaders(String version, String method, String name) {
		var headers = new java.util.LinkedHashMap<String, List<String>>();
		headers.put("Content-Type", List.of("application/json"));
		headers.put("Accept", List.of("application/json"));
		headers.put(McpProtocol.HEADER_PROTOCOL_VERSION, List.of(version));
		headers.put(McpProtocol.HEADER_METHOD, List.of(method));
		if (name != null) {
			headers.put(McpProtocol.HEADER_NAME, List.of(name));
		}
		// 下游自带的「不该透传」的头：
		headers.put("Cookie", List.of("session=caller-session"));
		headers.put("Authorization", List.of("Bearer caller-token"));
		headers.put("X-LLMGW-Internal", List.of("internal-value"));
		return headers;
	}

	private static JsonNode parseBody(McpHttpResponse response) {
		return MAPPER.readTree(new String(response.body(), StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------
	// 用例：
	// ------------------------------------------------------------------

	@Test
	void happyPathReconstructsUpstreamAndStripsDownstreamHeaders() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(settings(upstream, false, "up-secret",
				List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		McpHttpResponse response = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(200, response.status());
		assertEquals(1, upstream.received.size());
		Received sent = upstream.received.getFirst();
		assertEquals("Bearer up-secret", sent.firstHeader("Authorization"));
		assertEquals("tools/call", sent.firstHeader(McpProtocol.HEADER_METHOD));
		assertEquals("get_weather", sent.firstHeader(McpProtocol.HEADER_NAME));
		assertEquals("2026-07-28", sent.firstHeader(McpProtocol.HEADER_PROTOCOL_VERSION));
		// 下游 Cookie / 调用方凭证 / 内部头绝不透传（§9.3.6）。
		assertNull(sent.firstHeader("Cookie"));
		assertFalse(sent.body.contains("caller-token"));
		assertFalse(sent.body.contains("internal-value"));
		assertTrue(parseBody(response).path("result").path("ok").asBoolean());
	}

	@Test
	void missingMetaProtocolVersionIsRejected() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		String body = toolsCallBody("get_weather")
			.replace("\"" + McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\",", "");
		McpHttpResponse response = gateway
			.handle(post("/mcp/tools", mirrorHeaders("2026-07-28", "tools/call", "get_weather"), body));
		assertEquals(400, response.status());
		assertEquals(0, upstream.received.size());
	}

	@Test
	void unsupportedProtocolVersionIsRejected() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		String body = toolsCallBody("get_weather").replace("\"2026-07-28\"", "\"2025-06-18\"");
		McpHttpResponse response = gateway
			.handle(post("/mcp/tools", mirrorHeaders("2025-06-18", "tools/call", "get_weather"), body));
		assertEquals(400, response.status());
		assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("unsupported mcp protocol version"));
	}

	@Test
	void clientCapabilitiesRequiredEvenWhenEmpty() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		String body = toolsCallBody("get_weather").replace(",\"" + McpProtocol.META_CLIENT_CAPABILITIES + "\":{}", "");
		McpHttpResponse response = gateway
			.handle(post("/mcp/tools", mirrorHeaders("2026-07-28", "tools/call", "get_weather"), body));
		assertEquals(400, response.status());
	}

	@Test
	void protocolVersionHeaderMustMatchBody() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		McpHttpResponse missing = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				without(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_PROTOCOL_VERSION),
				toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(400, missing.status());
		McpHttpResponse mismatched = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_PROTOCOL_VERSION,
						"2025-06-18"),
				toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(400, mismatched.status());
	}

	@Test
	void mcpMethodHeaderRequiredAndMirrored() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		McpHttpResponse missing = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				without(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_METHOD),
				toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(400, missing.status());
		McpHttpResponse mismatched = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_METHOD, "tools/list"),
				toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(400, mismatched.status());
	}

	@Test
	void nameMirrorRequiredForNamedMethodsAndMustMatchOwnParameter() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		// 缺失 Mcp-Name：
		assertEquals(400, gateway
			.handle(new McpHttpRequest("POST", "/mcp/tools",
					without(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_NAME),
					toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"))
			.status());
		// 与 params.name 不一致：
		assertEquals(400,
				gateway
					.handle(new McpHttpRequest("POST", "/mcp/tools",
							with(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), McpProtocol.HEADER_NAME,
									"other_tool"),
							toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1",
							"project-1"))
					.status());
		// resources/read 用 params.uri 镜像：
		String readBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{" + "\"_meta\":{\""
				+ McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\"," + "\"" + McpProtocol.META_CLIENT_CAPABILITIES
				+ "\":{}}," + "\"uri\":\"file:///data/report.csv\"}}";
		McpHttpResponse read = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "resources/read", "file:///data/report.csv"),
						McpProtocol.HEADER_METHOD, "resources/read")
					.entrySet()
					.stream()
					.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> new ArrayList<>(e.getValue()),
							(a, b) -> a, java.util.LinkedHashMap::new)),
				readBody.getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(200, read.status());
	}

	@Test
	void nonNamedMethodsDoNotRequireNameMirror() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		// ping 携带 params.name 也不做名称校验（不得把所有名称字段解释为 params.name）。
		String pingBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{" + "\"_meta\":{\""
				+ McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\"," + "\"" + McpProtocol.META_CLIENT_CAPABILITIES
				+ "\":{}}," + "\"name\":\"not-a-target\"}}";
		McpHttpResponse response = gateway
			.handle(new McpHttpRequest("POST", "/mcp/tools", mirrorHeaders("2026-07-28", "ping", null),
					pingBody.getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(200, response.status());
	}

	@Test
	void defaultDenyWhenNoRuleMatches() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(settings(upstream, false, "", List.of(), List.of(), 0));
		McpHttpResponse response = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(403, response.status());
		JsonNode body = parseBody(response);
		assertEquals(-32600, body.path("error").path("code").asInt());
		assertTrue(body.path("error").path("message").asString().contains("default deny"));
		assertEquals(0, upstream.received.size());
	}

	@Test
	void unauthenticatedSubjectIsAlwaysDenied() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpSettings settings = settings(upstream, false, "", List.of(new McpRule("*", "tools", "*", "allow")),
				List.of(), 0);
		McpGatewayService gateway = service(settings);
		McpHttpResponse response = gateway
			.handle(new McpHttpRequest("POST", "/mcp/tools", mirrorHeaders("2026-07-28", "tools/call", "get_weather"),
					toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "", "tenant-1", "project-1"));
		assertEquals(403, response.status());
	}

	@Test
	void toolsListIsFilteredAndCallRecheckedIndependently() throws IOException {
		FakeUpstream upstream = modernUpstream();
		upstream.targetResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
				+ "{\"name\":\"read_orders\"},{\"name\":\"delete_orders\"},{\"name\":\"refund\"}]}}";
		McpSettings settings = settings(upstream, false, "up-secret",
				List.of(new McpRule("user-1", "tools", "delete_orders", "deny"),
						new McpRule("user-1", "tools", "refund", "deny"), new McpRule("user-1", "tools", "*", "allow")),
				List.of(), 0);
		McpGatewayService gateway = service(settings);
		String listBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + "\"_meta\":{\""
				+ McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\"," + "\"" + McpProtocol.META_CLIENT_CAPABILITIES
				+ "\":{}}}}";
		McpHttpResponse list = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "tools/list", null), McpProtocol.HEADER_METHOD, "tools/list")
					.entrySet()
					.stream()
					.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> new ArrayList<>(e.getValue()),
							(a, b) -> a, java.util.LinkedHashMap::new)),
				listBody.getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(200, list.status());
		JsonNode tools = parseBody(list).path("result").path("tools");
		assertEquals(1, tools.size());
		assertEquals("read_orders", tools.get(0).path("name").asString());
		// 即使客户端猜到未列出的工具名，tools/call 仍独立鉴权拒绝。
		McpHttpResponse denied = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "delete_orders"), toolsCallBody("delete_orders")));
		assertEquals(403, denied.status());
	}

	@Test
	void highRiskToolSuspendsForApprovalInsteadOfExecuting() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpSettings settings = settings(upstream, false, "up-secret",
				List.of(new McpRule("user-1", "tools", "refund", "require-approval")), List.of(), 0);
		McpGatewayService gateway = service(settings);
		McpHttpResponse response = gateway
			.handle(post("/mcp/tools", mirrorHeaders("2026-07-28", "tools/call", "refund"), toolsCallBody("refund")));
		assertEquals(202, response.status());
		JsonNode body = parseBody(response);
		assertTrue(body.path("approvalRequired").asBoolean());
		assertEquals("tools/refund", body.path("approvalTarget").asString());
		assertEquals(0, upstream.received.size());
	}

	@Test
	void legacyBridgePerformsHandshakePerExchangeAndDiscardsSession() throws IOException {
		FakeUpstream upstream = legacyUpstream();
		McpGatewayService gateway = service(
				settings(upstream, true, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		McpHttpResponse first = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(200, first.status());
		McpHttpResponse second = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(200, second.status());
		// 单次交换隔离：两次下游调用各自完成一次 initialize 握手。
		long initializes = upstream.received.stream().filter(r -> r.body.contains("\"method\":\"initialize\"")).count();
		assertEquals(2, initializes);
		// 目标调用带上游会话头；通知也带（同一交换内）。
		Received targetCall = upstream.received.getLast();
		assertNotNull(targetCall.firstHeader(McpProtocol.HEADER_LEGACY_SESSION));
	}

	@Test
	void oversizedBodyIsRejectedBeforeUpstream() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 64));
		McpHttpResponse response = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(400, response.status());
		assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("exceeds 64 bytes"));
	}

	@Test
	void disabledExtensionsReturnExplicitError() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		String body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"subscriptions/listen\",\"params\":{" + "\"_meta\":{\""
				+ McpProtocol.META_PROTOCOL_VERSION + "\":\"2026-07-28\"," + "\"" + McpProtocol.META_CLIENT_CAPABILITIES
				+ "\":{}}}}";
		McpHttpResponse response = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "subscriptions/listen", null), McpProtocol.HEADER_METHOD,
						"subscriptions/listen")
					.entrySet()
					.stream()
					.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> new ArrayList<>(e.getValue()),
							(a, b) -> a, java.util.LinkedHashMap::new)),
				body.getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(403, response.status());
		assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("disabled by default"));
	}

	@Test
	void originOutsideAllowListIsRejected() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(settings(upstream, false, "",
				List.of(new McpRule("user-1", "tools", "*", "allow")), List.of("https://console.example"), 0));
		McpHttpResponse response = gateway.handle(new McpHttpRequest("POST", "/mcp/tools",
				with(mirrorHeaders("2026-07-28", "tools/call", "get_weather"), "Origin", "https://evil.example"),
				toolsCallBody("get_weather").getBytes(StandardCharsets.UTF_8), "user-1", "tenant-1", "project-1"));
		assertEquals(403, response.status());
	}

	@Test
	void unknownServerRouteIs404AndWrongMethodIs405() throws IOException {
		FakeUpstream upstream = modernUpstream();
		McpGatewayService gateway = service(
				settings(upstream, false, "", List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		assertEquals(404,
				gateway
					.handle(post("/mcp/unknown", mirrorHeaders("2026-07-28", "tools/call", "get_weather"),
							toolsCallBody("get_weather")))
					.status());
		assertEquals(405, gateway
			.handle(new McpHttpRequest("GET", "/mcp/tools", Map.of(), new byte[0], "user-1", "tenant-1", "project-1"))
			.status());
	}

	@Test
	void sseUpstreamResultIsExtractedFromLastDataFrame() throws IOException {
		FakeUpstream upstream = modernUpstream();
		upstream.targetContentType = "text/event-stream";
		upstream.targetResponse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"partial\":true}\n\n"
				+ "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"done\":true}}\n\n";
		McpGatewayService gateway = service(settings(upstream, false, "up-secret",
				List.of(new McpRule("user-1", "tools", "*", "allow")), List.of(), 0));
		McpHttpResponse response = gateway.handle(post("/mcp/tools",
				mirrorHeaders("2026-07-28", "tools/call", "get_weather"), toolsCallBody("get_weather")));
		assertEquals(200, response.status());
		assertEquals("true", parseBody(response).path("result").path("done").asString());
	}

	@Test
	void configuredPolicyPrefixWildcardsMatch() {
		assertTrue(ConfiguredMcpAccessPolicy.matches("user-*", "user-17"));
		assertTrue(ConfiguredMcpAccessPolicy.matches("*", "anything"));
		assertFalse(ConfiguredMcpAccessPolicy.matches("user-1", "user-17"));
		ConfiguredMcpAccessPolicy policy = new ConfiguredMcpAccessPolicy(new McpSettings(List.of(),
				List.of(new McpRule("user-*", "tools", "read_*", "allow")), 0, List.of(), 0));
		assertEquals(McpAccessPolicy.McpDecision.Type.ALLOW, policy
			.check(new McpAccessPolicy.McpAccessQuery("user-9", "t", "p", "tools/call", "tools", "read_orders", ""))
			.type());
		assertEquals(McpAccessPolicy.McpDecision.Type.DENY, policy
			.check(new McpAccessPolicy.McpAccessQuery("user-9", "t", "p", "tools/call", "tools", "delete_orders", ""))
			.type());
	}

	// ------------------------------------------------------------------
	// 小工具：
	// ------------------------------------------------------------------

	private static Map<String, List<String>> without(Map<String, List<String>> headers, String name) {
		var out = new java.util.LinkedHashMap<String, List<String>>();
		headers.forEach((k, v) -> {
			if (!k.equalsIgnoreCase(name)) {
				out.put(k, v);
			}
		});
		return out;
	}

	private static Map<String, List<String>> with(Map<String, List<String>> headers, String name, String value) {
		var out = new java.util.LinkedHashMap<String, List<String>>();
		out.putAll(headers);
		out.put(name, List.of(value));
		return out;
	}

}
