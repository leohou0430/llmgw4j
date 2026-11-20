package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.SnapshotStore;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.UsageSummaries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Zero-dependency persistence: exports usage counters to a JSON file and reloads them on
 * demand via POST /admin/snapshot/load. Restore is deliberately MANUAL: auto-loading at
 * startup would inject a stale ledger into every benchmark arm (each arm starts a fresh
 * process), turning quota into an uncontrolled variable. Production builds should replace
 * this with a JDBC/Redis implementation behind the same SnapshotStore interface.
 */
public class JsonSnapshotStore implements SnapshotStore {

	private static final Logger log = LoggerFactory.getLogger(JsonSnapshotStore.class);

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final UsageLedger ledger;

	private final Path path;

	public JsonSnapshotStore(UsageLedger ledger, GatewayProperties properties) {
		this.ledger = ledger;
		this.path = Path.of(GovernanceLimits.from(properties).snapshotPath());
	}

	@Override
	public String exportJson() {
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode usage = root.putObject("usage");
		ledger.snapshot().forEach((key, summary) -> {
			ObjectNode item = usage.putObject(key);
			item.put("requests", summary.requests());
			item.put("promptTokens", summary.promptTokens());
			item.put("completionTokens", summary.completionTokens());
			item.put("errors", summary.errors());
		});
		return MAPPER.writeValueAsString(root);
	}

	@Override
	public void importJson(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		}
		catch (RuntimeException invalid) {
			throw new IllegalArgumentException("invalid snapshot JSON", invalid);
		}
		if (root == null || !root.isObject() || !root.path("usage").isObject()) {
			throw new IllegalArgumentException("snapshot requires a usage object");
		}
		Map<String, UsageLedger.UsageSummary> data = new HashMap<>();
		for (Map.Entry<String, JsonNode> entry : root.get("usage").properties()) {
			JsonNode item = entry.getValue();
			if (!item.isObject())
				throw new IllegalArgumentException("snapshot usage entry must be an object");
			data.put(entry.getKey(), UsageSummaries.of(counter(item, "requests"), counter(item, "promptTokens"),
					counter(item, "completionTokens"), counter(item, "errors")));
		}
		UsageSummaries.validateSnapshot(data);
		ledger.load(data);
	}

	private static long counter(JsonNode item, String name) {
		JsonNode value = item.path(name);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
			throw new IllegalArgumentException("snapshot counter must be a nonnegative long: " + name);
		}
		return value.longValue();
	}

	@Override
	public void save() {
		Path pending = null;
		try {
			Path target = path.toAbsolutePath();
			Files.createDirectories(target.getParent());
			pending = Files.createTempFile(target.getParent(), "llmgw-snapshot-", ".tmp");
			Files.writeString(pending, exportJson());
			Files.move(pending, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		catch (java.io.IOException error) {
			throw new java.io.UncheckedIOException("failed to save snapshot", error);
		}
		finally {
			if (pending != null) {
				try {
					Files.deleteIfExists(pending);
				}
				catch (java.io.IOException cleanup) {
					log.warn("Failed to remove temporary snapshot: {}", cleanup.getMessage());
				}
			}
		}
	}

	@Override
	public void load() {
		try {
			importJson(Files.readString(path));
		}
		catch (java.io.IOException error) {
			throw new java.io.UncheckedIOException("failed to load snapshot", error);
		}
	}

}
