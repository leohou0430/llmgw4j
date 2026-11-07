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
 * Zero-dependency persistence: exports usage counters to a JSON file and reloads
 * them on demand via POST /admin/snapshot/load. Restore is deliberately MANUAL:
 * auto-loading at startup would inject a stale ledger into every benchmark arm
 * (each arm starts a fresh process), turning quota into an uncontrolled variable.
 * Production builds should replace this with a JDBC/Redis implementation behind
 * the same SnapshotStore interface.
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
        try {
            JsonNode usage = MAPPER.readTree(json).get("usage");
            if (usage == null) {
                return;
            }
            Map<String, UsageLedger.UsageSummary> data = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : usage.properties()) {
                JsonNode item = entry.getValue();
                data.put(entry.getKey(), UsageSummaries.of(
                        item.path("requests").asLong(),
                        item.path("promptTokens").asLong(),
                        item.path("completionTokens").asLong(),
                        item.path("errors").asLong()));
            }
            ledger.load(data);
        } catch (RuntimeException e) {
            log.warn("Failed to import snapshot: {}", e.getMessage());
        }
    }

    @Override
    public void save() {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, exportJson());
        } catch (Exception e) {
            log.warn("Failed to save snapshot to {}: {}", path, e.getMessage());
        }
    }

    @Override
    public void load() {
        try {
            if (Files.isReadable(path)) {
                importJson(Files.readString(path));
            }
        } catch (Exception e) {
            log.warn("Failed to load snapshot from {}: {}", path, e.getMessage());
        }
    }
}
