package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Zero-dependency JSON persistence for runtime-created API keys, implementing
 * the {@link io.github.leohou0430.llmgw.core.governance.KeyStore} SPI.
 *
 * <p>B1 at-rest mode: with {@code gateway.auth.hash-keys-at-rest=true} (default)
 * entries are keyed by SHA-256(token) — the file never holds a usable token
 * (LiteLLM-style hashed vault). Plaintext files written by earlier versions
 * are migrated to hashes on first load. best-effort 0600 on POSIX.
 * An empty {@code gateway.keys-store-path} disables it.
 */
public class JsonKeyStore implements io.github.leohou0430.llmgw.core.governance.KeyStore {

    private static final Logger log = LoggerFactory.getLogger(JsonKeyStore.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path path;
    private final boolean hashAtRest;

    public JsonKeyStore(GatewayProperties properties) {
        String configured = properties.getKeysStorePath();
        this.path = configured == null || configured.isBlank()
                ? null
                : Path.of(configured);
        this.hashAtRest = properties.getAuth().isHashKeysAtRest();
    }

    /** B1: the at-rest key for a plaintext token (hash or plaintext per mode). */
    private String storeKey(String token) {
        if (!hashAtRest) {
            return token;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean enabled() {
        return path != null;
    }

    @Override
    public void upsert(String token, ApiKeyRegistry.ApiKey key) {
        update(root -> putKey(root, storeKey(token), key));
    }

    private static void putKey(ObjectNode root, String atRest, ApiKeyRegistry.ApiKey key) {
        ObjectNode item = root.putObject(atRest);
        item.put("id", key.id());
        item.put("name", key.name());
        item.put("quotaTokens", key.quotaTokens());
        ArrayNode models = item.putArray("allowedModels");
        key.allowedModels().forEach(models::add);
        item.put("expiresAtEpochMs", key.expiresAtEpochMs());
        item.put("group", key.group());
        item.put("budgetMicroUsd", key.budgetMicroUsd());
        ObjectNode metadata = item.putObject("metadata");
        key.metadata().forEach(metadata::put);
    }

    @Override
    public void delete(String token) {
        deleteAtRest(storeKey(token));
    }

    @Override
    public void rotate(String oldToken, String newToken, long overlapEnd) {
        rotateAtRest(storeKey(oldToken), storeKey(newToken), overlapEnd);
    }

    @Override
    public void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd) {
        update(root -> markRotated(root, oldAtRest, newAtRest, overlapEnd));
    }

    private static void markRotated(ObjectNode root, String oldAtRest, String newAtRest, long overlapEnd) {
        ObjectNode oldItem = (ObjectNode) root.get(oldAtRest);
        if (oldItem != null) {
            oldItem.put("rotatedTo", newAtRest);
            oldItem.put("legacyUntil", overlapEnd);
        }
    }

    /** Publish both generations together so a crash cannot split a rotation. */
    void rotateKey(String oldAtRest, String newToken, ApiKeyRegistry.ApiKey key, long overlapEnd) {
        update(root -> {
            String newAtRest = storeKey(newToken);
            markRotated(root, oldAtRest, newAtRest, overlapEnd);
            putKey(root, newAtRest, key);
        });
    }

    void revokeIdentity(String keyId) {
        update(root -> {
            var entries = new java.util.ArrayList<String>();
            root.properties().forEach(entry -> {
                if (keyId.equals(entry.getValue().path("id").asText(""))) entries.add(entry.getKey());
            });
            entries.forEach(root::remove);
        });
    }

    @Override
    public void deleteAtRest(String atRestKey) {
        update(root -> root.remove(atRestKey));
    }

    private void update(java.util.function.Consumer<ObjectNode> change) {
        if (path == null) return;
        // Key management is infrequent; one process lock also coordinates separate store instances.
        synchronized (JsonKeyStore.class) {
            try {
                ObjectNode root = readRoot();
                change.accept(root);
                write(root);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist API key state to " + path, e);
            }
        }
    }

    /** store-key -> entry; expired-legacy entries are kept (find() filters). */
    @Override
    public Map<String, io.github.leohou0430.llmgw.core.governance.KeyStore.StoredKey> load() {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        Map<String, io.github.leohou0430.llmgw.core.governance.KeyStore.StoredKey> out =
                new LinkedHashMap<>();
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(path));
            for (Map.Entry<String, tools.jackson.databind.JsonNode> entry : root.properties()) {
                ObjectNode item = (ObjectNode) entry.getValue();
                ApiKeyRegistry.ApiKey key = new ApiKeyRegistry.ApiKey(
                        item.path("id").asText(""),
                        item.path("name").asText(""),
                        item.path("quotaTokens").asLong(0),
                        Set.copyOf(stringList(item)),
                        item.path("expiresAtEpochMs").asLong(0),
                        item.path("group").asText(""),
                        item.path("budgetMicroUsd").asLong(0),
                        metadataOf(item));
                String rotatedTo = item.path("rotatedTo").asText("");
                long legacyUntil = item.path("legacyUntil").asLong(0);
                out.put(entry.getKey(),
                        new io.github.leohou0430.llmgw.core.governance.KeyStore.StoredKey(
                                key, rotatedTo.isEmpty() ? null : rotatedTo,
                                legacyUntil > 0 ? legacyUntil : 0));
            }
            if (!out.isEmpty()) {
                log.info("Restored {} runtime API key(s) from {}", out.size(), path);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load API key state from " + path, e);
        }
        return out;
    }

    private static Map<String, String> metadataOf(ObjectNode item) {
        Map<String, String> out = new LinkedHashMap<>();
        var node = item.get("metadata");
        if (node != null && node.isObject()) {
            for (Map.Entry<String, tools.jackson.databind.JsonNode> e2 : node.properties()) {
                out.put(e2.getKey(), e2.getValue().asText());
            }
        }
        return out;
    }

    private static java.util.List<String> stringList(ObjectNode item) {
        java.util.List<String> out = new java.util.ArrayList<>();
        item.withArray("allowedModels").forEach(node -> out.add(node.asText()));
        return out;
    }

    private ObjectNode readRoot() throws Exception {
        if (!Files.exists(path)) {
            return MAPPER.createObjectNode();
        }
        return (ObjectNode) MAPPER.readTree(Files.readString(path));
    }

    private void write(ObjectNode root) throws Exception {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp;
        try {
            temp = Files.createTempFile(absolute.getParent(), ".llmgw-keys-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            temp = Files.createTempFile(absolute.getParent(), ".llmgw-keys-", ".tmp");
        }
        try {
            Files.writeString(temp, MAPPER.writeValueAsString(root));
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, absolute, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
