package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.KeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.3 seam: the SQLite {@link KeyStore} satisfies the same observable contract as
 * the JSON store (at-rest hashing, rotation linkage, legacy window, full ApiKey
 * round-trip) and can be installed by explicit {@code replace} before seal.
 */
class SqliteKeyStoreTest {

    private static final String SHA256_SK_TEST =
            "389faa3c31e10c880a5b4a506725160fd5165f1cd85e65619d12bcfde5296e9b";

    @TempDir
    Path tempDir;

    private SqliteContext databaseWithSchema(String name) throws Exception {
        SqliteContext database = new SqliteContext(tempDir.resolve(name), 1000L);
        new SchemaMigrator(database).migrate(Migrations.all());
        return database;
    }

    private static ApiKeyRegistry.ApiKey sampleKey() {
        return new ApiKeyRegistry.ApiKey("k1", "Primary", 1000L,
                Set.of("deepseek-chat", "deepseek-reasoner"), 0L, "team-a", 5_000_000L,
                Map.of("env", "prod"));
    }

    @Test
    void upsertAndLoadRoundTripsEveryField() throws Exception {
        try (SqliteContext database = databaseWithSchema("roundtrip.db")) {
            SqliteKeyStore store = new SqliteKeyStore(database, false);
            store.upsert("sk-test-token", sampleKey());

            Map<String, KeyStore.StoredKey> loaded = store.load();
            assertEquals(1, loaded.size());
            KeyStore.StoredKey stored = loaded.get("sk-test-token");
            assertNotNull(stored);
            ApiKeyRegistry.ApiKey key = stored.key();
            assertEquals("k1", key.id());
            assertEquals("Primary", key.name());
            assertEquals(1000L, key.quotaTokens());
            assertEquals(Set.of("deepseek-chat", "deepseek-reasoner"), key.allowedModels());
            assertEquals(0L, key.expiresAtEpochMs());
            assertEquals("team-a", key.group());
            assertEquals(5_000_000L, key.budgetMicroUsd());
            assertEquals(Map.of("env", "prod"), key.metadata());
            assertNull(stored.rotatedTo());
            assertEquals(0L, stored.legacyUntil());
        }
    }

    @Test
    void hashesTokenAtRestWhenEnabled() throws Exception {
        try (SqliteContext database = databaseWithSchema("hash.db")) {
            SqliteKeyStore store = new SqliteKeyStore(database, true);
            store.upsert("sk-test-token", sampleKey());

            Map<String, KeyStore.StoredKey> loaded = store.load();
            assertTrue(loaded.containsKey(SHA256_SK_TEST), "expected hashed at-rest key");
            assertFalse(loaded.containsKey("sk-test-token"), "plaintext token must not be a key");
        }
    }

    @Test
    void deleteRemovesTheEntry() throws Exception {
        try (SqliteContext database = databaseWithSchema("delete.db")) {
            SqliteKeyStore store = new SqliteKeyStore(database, false);
            store.upsert("sk-test-token", sampleKey());
            store.delete("sk-test-token");
            assertTrue(store.load().isEmpty());
        }
    }

    @Test
    void rotateMarksOldEntryWithSuccessorAndLegacyWindow() throws Exception {
        try (SqliteContext database = databaseWithSchema("rotate.db")) {
            SqliteKeyStore store = new SqliteKeyStore(database, false);
            store.upsert("sk-test-token", sampleKey());
            store.rotate("sk-test-token", "sk-rotated-token", 1_700_000_000_000L);

            KeyStore.StoredKey old = store.load().get("sk-test-token");
            assertNotNull(old);
            assertEquals("sk-rotated-token", old.rotatedTo());
            assertEquals(1_700_000_000_000L, old.legacyUntil());
        }
    }

    @Test
    void deleteAtRestRemovesHashedEntry() throws Exception {
        try (SqliteContext database = databaseWithSchema("deleteatrest.db")) {
            SqliteKeyStore store = new SqliteKeyStore(database, true);
            store.upsert("sk-test-token", sampleKey());
            store.deleteAtRest(SHA256_SK_TEST);
            assertTrue(store.load().isEmpty());
        }
    }

    @Test
    void enabledIsTrueWhenBackedByADatabase() throws Exception {
        try (SqliteContext database = databaseWithSchema("enabled.db")) {
            assertTrue(new SqliteKeyStore(database, false).enabled());
        }
    }

    @Test
    void installsByExplicitReplaceBeforeSealAndFailsAfterInstantiation() throws Exception {
        try (SqliteContext database = databaseWithSchema("replace.db")) {
            try (ExtensionContext context = new ExtensionContext()) {
                context.bind(KeyStore.class, NoopKeyStore::new);
                context.replace(KeyStore.class, () -> new SqliteKeyStore(database, false));
                context.seal();
                assertInstanceOf(SqliteKeyStore.class, context.get(KeyStore.class));
            }
            try (ExtensionContext context = new ExtensionContext()) {
                context.bind(KeyStore.class, NoopKeyStore::new);
                context.get(KeyStore.class);
                assertThrows(IllegalStateException.class,
                        () -> context.replace(KeyStore.class, () -> new SqliteKeyStore(database, false)));
            }
        }
    }

    private static final class NoopKeyStore implements KeyStore {
        @Override public void upsert(String token, ApiKeyRegistry.ApiKey key) { }
        @Override public void delete(String token) { }
        @Override public void rotate(String oldToken, String newToken, long overlapEnd) { }
        @Override public void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd) { }
        @Override public Map<String, StoredKey> load() { return Map.of(); }
        @Override public boolean enabled() { return false; }
    }
}