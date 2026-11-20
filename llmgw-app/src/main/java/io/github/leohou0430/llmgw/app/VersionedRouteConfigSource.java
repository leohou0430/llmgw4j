package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.*;
import io.github.leohou0430.llmgw.control.local.DefaultConfigValidator;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.*;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.InetAddress;
import java.util.*;
import java.util.function.Consumer;

/** SQLite route versions activate one immutable source used by the real router. */
public final class VersionedRouteConfigSource implements RouteConfigSource {

	@FunctionalInterface
	public interface SecretResolver {

		String resolve(String tenant, String project, String id);

	}

	private volatile RouteTable table;

	private final SecretResolver secrets;

	private final boolean allowTestNetwork;

	private final Consumer<Route> routeValidator;

	public VersionedRouteConfigSource(List<Route> initial, SecretResolver secrets, boolean allowTestNetwork) {
		this(initial, secrets, allowTestNetwork, route -> {
			if (!route.provider().isEmpty() && !Set.of("openai", "anthropic").contains(route.provider()))
				throw new IllegalArgumentException("unknown provider: " + route.provider());
		});
	}

	public VersionedRouteConfigSource(List<Route> initial, SecretResolver secrets, boolean allowTestNetwork,
			Consumer<Route> routeValidator) {
		table = RouteTable.of(initial, 0);
		this.secrets = Objects.requireNonNull(secrets);
		this.allowTestNetwork = allowTestNetwork;
		this.routeValidator = Objects.requireNonNull(routeValidator);
	}

	@Override
	public RouteTable current() {
		return table;
	}

	/**
	 * 热更新（v10 语义在 v0.4 架构上的延续）：校验后的路由表原子 install， 返回新版本号。注意：此路径不写 SQLite 版本历史——持久化发布走
	 * RouteVersionService.publish；重启后回滚到 SQLite 最新已发布版本。
	 */
	@Override
	public synchronized long replace(java.util.List<Route> routes) {
		long next = table.version() + 1;
		install(RouteTable.of(routes, next));
		return next;
	}

	public Consumer<RouteVersion> prepare(String config) {
		RouteTable candidate = parse(config, 0);
		return version -> install(RouteTable.of(candidate.routes(), version.version()));
	}

	private synchronized void install(RouteTable next) {
		if (next.version() > table.version())
			table = next;
	}

	public void validate(String config) {
		parse(config, 0);
	}

	public synchronized void activate(RouteVersion version) {
		RouteTable next = parse(version.config(), version.version());
		if (next.version() > table.version())
			table = next;
	}

	private RouteTable parse(String config, long version) {
		JsonNode root;
		try {
			root = ProtocolCodec.mapper().readTree(config);
		}
		catch (RuntimeException e) {
			throw new IllegalArgumentException("route config must be JSON", e);
		}
		if (root == null || !root.isObject() || !root.path("routes").isArray() || root.path("routes").isEmpty())
			throw new IllegalArgumentException("nonempty routes array required");
		rejectPlaintextCredentials(root);
		List<Route> routes = new ArrayList<>();
		for (JsonNode node : root.path("routes")) {
			if (!node.isObject())
				throw new IllegalArgumentException("route must be an object");
			if (node.has("apiKey") || node.has("api_key") || node.has("authorization"))
				throw new IllegalArgumentException("plaintext upstream credentials cannot be published; use secretId");
			String id = text(node, "id", null), url = text(node, "targetUrl", null);
			if (id == null || id.isBlank() || url == null || url.isBlank())
				throw new IllegalArgumentException("route id and targetUrl required");
			URI uri;
			try {
				uri = URI.create(url);
			}
			catch (RuntimeException e) {
				throw new IllegalArgumentException("invalid targetUrl", e);
			}
			if (uri.getRawUserInfo() != null || uri.getFragment() != null)
				throw new IllegalArgumentException("targetUrl cannot contain user info or fragment");
			boolean mock = "mock".equals(uri.getScheme());
			if (mock) {
				if (!allowTestNetwork)
					throw new IllegalArgumentException("mock route forbidden");
			}
			else {
				PlatformPolicy policy = allowTestNetwork ? PlatformPolicy.of(Set.of("http", "https"), true)
						: PlatformPolicy.strict();
				var violations = new DefaultConfigValidator()
					.validate(UpstreamCandidate.of(id, uri.getScheme(), uri.getHost(), 30_000, 100), policy);
				if (!violations.isEmpty())
					throw new IllegalArgumentException(String.join("; ", violations));
				if (!allowTestNetwork)
					checkResolvedAddress(uri.getHost());
			}
			String secretId = text(node, "secretId", ""), key = "";
			if (!secretId.isBlank()) {
				String tenant = text(node, "tenantId", ""), project = text(node, "projectId", "");
				if (tenant.isBlank() || project.isBlank())
					throw new IllegalArgumentException("secret requires tenantId/projectId");
				key = secrets.resolve(tenant, project, secretId);
				if (key == null || key.isBlank())
					throw new IllegalArgumentException("unknown or empty secret reference");
			}
			else if (!allowTestNetwork)
				throw new IllegalArgumentException("production routes require secretId");
			List<String> fallbacks = new ArrayList<>();
			JsonNode fallback = node.path("fallbacks");
			if (!fallback.isMissingNode() && !fallback.isArray())
				throw new IllegalArgumentException("fallbacks must be an array");
			if (fallback.isArray())
				for (JsonNode item : fallback) {
					if (!item.isTextual())
						throw new IllegalArgumentException("fallback id must be text");
					fallbacks.add(item.asText());
				}
			int weight = 1;
			if (node.has("weight")) {
				var w = node.path("weight");
				if (!w.isIntegralNumber() || !w.canConvertToInt() || w.intValue() < 1)
					throw new IllegalArgumentException("positive weight required");
				weight = w.intValue();
			}
			Route route = new Route(id, text(node, "protocol", "*"), text(node, "model", "*"),
					text(node, "targetModel", ""), url, key, text(node, "provider", ""), weight, List.copyOf(fallbacks),
					text(node, "upstreamProtocol", ""));
			routeValidator.accept(route);
			routes.add(route);
		}
		var ids = new HashSet<String>();
		for (Route route : routes)
			ids.add(route.id());
		for (Route route : routes)
			for (String target : route.fallbackIds())
				if (!ids.contains(target) || target.equals(route.id()))
					throw new IllegalArgumentException("unknown/self fallback: " + target);
		return RouteTable.of(routes, version);
	}

	private static void rejectPlaintextCredentials(JsonNode node) {
		if (node.isObject() && (node.has("apiKey") || node.has("api_key") || node.has("authorization")))
			throw new IllegalArgumentException("plaintext upstream credentials cannot be published; use secretId");
		if ((node.isObject() || node.isArray()))
			for (JsonNode child : node)
				rejectPlaintextCredentials(child);
	}

	private static String text(JsonNode node, String field, String fallback) {
		var value = node.path(field);
		if (value.isMissingNode())
			return fallback;
		if (!value.isTextual())
			throw new IllegalArgumentException(field + " must be text");
		return value.asText();
	}

	private static void checkResolvedAddress(String host) {
		try {
			for (InetAddress address : InetAddress.getAllByName(host))
				if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
						|| address.isSiteLocalAddress() || address.isMulticastAddress()
						|| ((address.getAddress()[0] & 0xfe) == 0xfc))
					throw new IllegalArgumentException("private upstream address forbidden");
		}
		catch (java.net.UnknownHostException e) {
			throw new IllegalArgumentException("upstream host cannot be resolved", e);
		}
	}

}
