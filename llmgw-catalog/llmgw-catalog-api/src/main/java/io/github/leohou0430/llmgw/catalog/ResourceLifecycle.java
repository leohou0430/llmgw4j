package io.github.leohou0430.llmgw.catalog;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.Route;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** The catalog owns routing snapshots; wire responses contain only provider objects. */
public final class ResourceLifecycle {

	private final ResourceCatalog catalog;

	private final ApiKeyRegistry keys;

	private Scope frozenScope;

	public ResourceLifecycle(ResourceCatalog catalog, ApiKeyRegistry keys) {
		this.catalog = Objects.requireNonNull(catalog);
		this.keys = Objects.requireNonNull(keys);
	}

	public ResourceLifecycle scoped(io.github.leohou0430.llmgw.core.governance.GatewayDecision admission) {
		var scoped = new ResourceLifecycle(catalog, keys);
		scoped.frozenScope = admission.projectId() != null && !admission.projectId().isBlank()
				? new Scope(admission.tenantId(), admission.projectId()) : scope(admission.keyId());
		return scoped;
	}

	private record Scope(String tenant, String project) {
	}

	private Scope scope(String keyId) {
		if (frozenScope != null)
			return frozenScope;
		var key = keys.all().stream().filter(k -> k.id().equals(keyId)).findFirst().orElseThrow();
		var metadata = key.metadata() == null ? Map.<String, String>of() : key.metadata();
		return new Scope(metadata.getOrDefault("llmgw.tenantId", keyId),
				metadata.getOrDefault("llmgw.projectId", keyId));
	}

	private ResourceEntry entry(String key, String id) {
		Scope scope = scope(key);
		return catalog.get(scope.project(), id)
			.filter(e -> !e.deleted() && scope.tenant().equals(e.metadata().get("llmgw.tenantId")))
			.orElse(null);
	}

	public Route route(String key, ProtocolKind kind, String id) {
		var entry = entry(key, id);
		if (entry == null || !kind.wireName().equals(entry.type()))
			return null;
		return ProtocolCodec.mapper().readValue(entry.metadata().get("llmgw.sourceRoute"), Route.class);
	}

	public void created(String key, ProtocolKind kind, Route route, String body) {
		if (kind != ProtocolKind.FILES && kind != ProtocolKind.BATCHES)
			return;
		JsonNode object = ProtocolCodec.tryParse(body);
		String id = object.path("id").asText("");
		if (id.isBlank())
			return;
		Scope scope = scope(key);
		long now = System.currentTimeMillis();
		catalog.create(new ResourceEntry(id, scope.project(), kind.wireName(), id, route.id(), route.provider(),
				Integer.toHexString(route.hashCode()), now, object.path("status").asText(""), Map.of("llmgw.tenantId",
						scope.tenant(), "llmgw.sourceRoute", ProtocolCodec.toJson(route), "llmgw.object", body),
				now, false));
		registerBatchFiles(key, kind, route, object);
	}

	private void registerBatchFiles(String key, ProtocolKind kind, Route route, JsonNode batch) {
		if (kind != ProtocolKind.BATCHES)
			return;
		for (String field : List.of("output_file_id", "error_file_id")) {
			String id = batch.path(field).asText("");
			if (!id.isBlank() && entry(key, id) == null) {
				created(key, ProtocolKind.FILES, route,
						ProtocolCodec.toJson(Map.of("id", id, "object", "file", "purpose", "batch_output")));
			}
		}
	}

	public void observed(String key, ProtocolKind kind, String id, Route route, String body, boolean deleted) {
		var old = entry(key, id);
		if (old == null)
			return;
		if (deleted) {
			catalog.delete(old.projectId(), id);
			return;
		}
		JsonNode object = ProtocolCodec.tryParse(body);
		if (!id.equals(object.path("id").asText("")))
			return;
		var metadata = new HashMap<>(old.metadata());
		metadata.put("llmgw.object", body);
		catalog.create(new ResourceEntry(old.gatewayId(), old.projectId(), old.type(), old.upstreamId(),
				old.sourceRoute(), old.provider(), old.configVersion(), old.createdAt(),
				object.path("status").asText(old.observableStatus()), metadata, System.currentTimeMillis(), false));
		registerBatchFiles(key, kind, route, object);
	}

	public String list(String key, ProtocolKind kind, String limit, String cursor) {
		Scope scope = scope(key);
		int size = limit == null ? 20 : Integer.parseInt(limit);
		PageRequest request = PageRequest.of(size, kind.wireName());
		CatalogPage page;
		if (cursor != null && !cursor.isEmpty() && !cursor.contains(".")) {
			// Provider SDKs send after=last_id. Walk bounded signed pages to retain
			// scope filtering without an unbounded catalog.list() materialization.
			String continuation = null;
			boolean found = false;
			var selected = new ArrayList<ResourceEntry>();
			do {
				var scanned = catalog.page(scope.tenant(), scope.project(), PageRequest.of(1, kind.wireName()),
						continuation);
				for (var item : scanned.items()) {
					if (found)
						selected.add(item);
					else if (cursor.equals(item.gatewayId())
							&& scope.tenant().equals(item.metadata().get("llmgw.tenantId")))
						found = true;
				}
				continuation = scanned.nextCursor();
			}
			while (continuation != null && selected.size() < size);
			if (!found)
				throw new IllegalArgumentException("after resource not found");
			page = new CatalogPage(selected, continuation);
		}
		else {
			page = catalog.page(scope.tenant(), scope.project(), request, cursor);
		}
		var data = page.items()
			.stream()
			.filter(e -> scope.tenant().equals(e.metadata().get("llmgw.tenantId")))
			.map(e -> ProtocolCodec.tryParse(e.metadata().getOrDefault("llmgw.object", "{}")))
			.toList();
		var response = new LinkedHashMap<String, Object>();
		response.put("object", "list");
		response.put("data", data);
		response.put("has_more", page.nextCursor() != null);
		response.put("first_id", data.isEmpty() ? null : data.getFirst().path("id").asText(""));
		response.put("last_id", data.isEmpty() ? null : data.getLast().path("id").asText(""));
		response.put("next_cursor", page.nextCursor());
		return ProtocolCodec.toJson(response);
	}

	public Route referencedRoute(ParsedRequest request, String key, Route fallback) {
		JsonNode node = ProtocolCodec.tryParse(request.rawJson());
		Route source = firstFile(node, key);
		return source == null ? fallback
				: new Route(source.id(), fallback.protocol(), fallback.model(), fallback.targetModel(),
						source.targetUrl(), source.apiKey(), source.provider(), fallback.weight(), List.of(),
						fallback.upstreamProtocol());
	}

	private Route firstFile(JsonNode node, String key) {
		if (node == null)
			return null;
		if (node.isObject()) {
			for (var field : node.properties()) {
				Route found = field.getKey().equals("file_id") || field.getKey().equals("input_file_id")
						? route(key, ProtocolKind.FILES, field.getValue().asText(""))
						: firstFile(field.getValue(), key);
				if (found != null)
					return found;
			}
		}
		else if (node.isArray()) {
			for (JsonNode item : node) {
				Route found = firstFile(item, key);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	public boolean ownsFiles(ParsedRequest request, String key, Route route) {
		return owns(ProtocolCodec.tryParse(request.rawJson()), key, route);
	}

	private boolean owns(JsonNode node, String key, Route route) {
		if (node == null)
			return true;
		if (node.isObject()) {
			for (var field : node.properties()) {
				if (field.getKey().equals("file_id") || field.getKey().equals("input_file_id")) {
					Route source = route(key, ProtocolKind.FILES, field.getValue().asText(""));
					if (source == null || !source.targetUrl().equals(route.targetUrl())
							|| !Objects.equals(source.apiKey(), route.apiKey()))
						return false;
				}
				else if (!owns(field.getValue(), key, route))
					return false;
			}
		}
		else if (node.isArray()) {
			for (JsonNode item : node)
				if (!owns(item, key, route))
					return false;
		}
		return true;
	}

}
