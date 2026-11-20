package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Route resolution shared by both data planes' blocking fallback loops. */
public final class EngineRoutes {

	public static boolean ownsReferencedFiles(ParsedRequest request, String tenantId, Route route,
			io.github.leohou0430.llmgw.core.routing.TaskRouteSticky store) {
		for (String id : referencedFiles(request)) {
			if (id.isBlank() || route == null
					|| !route.id().equals(store.resolve(tenantId, ProtocolKind.FILES.wireName(), id))) {
				return false;
			}
		}
		return true;
	}

	/** Provider file ids cannot be moved to another upstream during fallback. */
	public static List<ForwardingPolicy.Attempt> forwardPlan(Router router, Route primary,
			ForwardingPolicy.RetryConfig retry, ParsedRequest request) {
		if (!referencedFiles(request).isEmpty()) {
			return primary == null ? List.of() : List.of(new ForwardingPolicy.Attempt(primary, 1, 0));
		}
		return ForwardingPolicy.plan(router, primary, retry, request.kind());
	}

	/**
	 * Chapter-9 §9.2.6（ADR-0009）：从请求体提取显式输出上界。chat 取 {@code max_tokens} /
	 * {@code max_completion_tokens}，responses 取 {@code max_output_tokens}，messages 取
	 * {@code max_tokens}。仅接受正整数； 缺失返回 empty——严格预算据此拒绝不可证明的预留。
	 */
	public static java.util.OptionalLong outputBoundTokens(ParsedRequest request) {
		var root = io.github.leohou0430.llmgw.core.protocol.ProtocolCodec.tryParse(request.rawJson());
		if (root == null) {
			return java.util.OptionalLong.empty();
		}
		for (String field : new String[] { "max_completion_tokens", "max_output_tokens", "max_tokens" }) {
			var node = root.path(field);
			if (node.canConvertToLong()) {
				long value = node.asLong(-1);
				if (value > 0) {
					return java.util.OptionalLong.of(value);
				}
			}
		}
		return java.util.OptionalLong.empty();
	}

	private static List<String> referencedFiles(ParsedRequest request) {
		String raw = request.rawJson();
		// Escaped field names still require JSON inspection; ordinary text needs no
		// second tree.
		if (raw == null || (!raw.contains("file_id") && !raw.contains("\\u"))) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		collectFiles(io.github.leohou0430.llmgw.core.protocol.ProtocolCodec.tryParse(raw), ids);
		return ids;
	}

	private static void collectFiles(tools.jackson.databind.JsonNode node, List<String> ids) {
		if (node == null)
			return;
		if (node.isObject()) {
			for (var field : node.properties()) {
				if ("file_id".equals(field.getKey()) || "input_file_id".equals(field.getKey())) {
					ids.add(field.getValue().isString() ? field.getValue().asString() : "");
				}
				else {
					collectFiles(field.getValue(), ids);
				}
			}
		}
		else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++)
				collectFiles(node.get(i), ids);
		}
	}

	private EngineRoutes() {
	}

	/** Primary first, then configured fallbacks, preserving declaration order. */
	public static List<Route> candidates(Router router, Route primary) {
		List<Route> candidates = new ArrayList<>();
		candidates.add(primary);
		candidates.addAll(router.fallbacks(primary));
		return candidates;
	}

	/**
	 * Build the upstream request for a route. {@code forRoute} applies the optional
	 * target-model rewrite as a tree rewrite so vendor extension fields survive
	 * byte-for-byte. The upstream path comes from the request's protocol kind:
	 * pass-through parity means /v1/messages forwards to the upstream's /messages, never
	 * to /chat/completions.
	 */
	public static UpstreamRequest request(String rawJson, ParsedRequest parsed, Route route, int timeoutMs) {
		return request(rawJson, parsed, route, timeoutMs, Map.of());
	}

	/** v13: same, carrying the HeaderPolicy-filtered client headers. */
	public static UpstreamRequest request(String rawJson, ParsedRequest parsed, Route route, int timeoutMs,
			Map<String, String> passHeaders) {
		return new UpstreamRequest(rawJson, route.targetUrl(), route.apiKey(), route.provider(), parsed.model(),
				parsed.stream(), timeoutMs, parsed.kind().upstreamPath(), UpstreamMethod.POST, passHeaders)
			.forRoute(route);
	}

	/**
	 * T1.12 protocol conversion: build the upstream request from an ALREADY converted
	 * payload ({@code PreparedConversion.upstreamJson()} / {@code upstreamPath()}). The
	 * entry codec rewrote the model upstream, so there is deliberately NO
	 * {@code forRoute} tree rewrite here and the path is the upstream protocol's path,
	 * not the caller's. The caller model is kept for governance/metrics; the wire body is
	 * the prepared payload verbatim.
	 *
	 * <p>
	 * The prepared payload is passed as plain strings so this foundation module keeps
	 * depending on llmgw-core only (the conversion handle lives in the engine
	 * implementations).
	 */
	public static UpstreamRequest convertedRequest(String upstreamJson, String upstreamPath, ParsedRequest parsed,
			Route route, int timeoutMs, Map<String, String> passHeaders) {
		return new UpstreamRequest(upstreamJson, route.targetUrl(), route.apiKey(), route.provider(), parsed.model(),
				parsed.stream(), timeoutMs, upstreamPath, UpstreamMethod.POST, passHeaders);
	}

	/** Bodyless GET (video poll/content) pinned to one route; no fallback. */
	public static UpstreamRequest fetch(Route route, String path, int timeoutMs) {
		return new UpstreamRequest("", route.targetUrl(), route.apiKey(), "", false, timeoutMs, path,
				UpstreamMethod.GET);
	}

	/**
	 * W3.5/W4.3: POST with a JSON body whose response is BINARY (audio/speech). Goes
	 * through the byte[] fetch path so the payload never rounds-trips a String (which
	 * would corrupt non-UTF-8 bytes). Governance still applies.
	 */
	public static UpstreamRequest postBytes(String rawJson, ParsedRequest parsed, Route route, int timeoutMs,
			Map<String, String> passHeaders) {
		return new UpstreamRequest(rawJson, route.targetUrl(), route.apiKey(), route.provider(), parsed.model(), false,
				timeoutMs, parsed.kind().upstreamPath(), UpstreamMethod.POST, passHeaders)
			.forRoute(route);
	}

	/**
	 * W4.3/T2.1: POST with a BINARY body (multipart uploads — transcriptions, files)
	 * carried as a cold byte stream. Byte-faithful both ways: no charset round-trip, no
	 * model rewrite. {@code bodyLength} is the exact byte count.
	 */
	public static UpstreamRequest postRawBytes(ByteStream body, long bodyLength, ParsedRequest parsed, Route route,
			int timeoutMs, Map<String, String> passHeaders) {
		return new UpstreamRequest("", route.targetUrl(), route.apiKey(), route.provider(), parsed.model(), false,
				timeoutMs, parsed.kind().upstreamPath(), UpstreamMethod.POST, passHeaders, body, bodyLength)
			.forRoute(route);
	}

	/** W4.3: bodyless GET/DELETE by explicit verb (file list/retrieve/delete). */
	public static UpstreamRequest methodFetch(Route route, String path, UpstreamMethod method, int timeoutMs) {
		return new UpstreamRequest("", route.targetUrl(), route.apiKey(), route.provider(), "", false, timeoutMs, path,
				method, Map.of())
			.forRoute(route);
	}

}
