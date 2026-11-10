package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.KeyAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13 key lifecycle: create returns the token exactly once, revoke kills
 * access, TTL expiry is enforced on the read path, and the JSON store
 * round-trips runtime keys across restarts. Config keys keep working
 * unchanged (static source re-registers them at boot).
 */
class ManagedApiKeyRegistryTest {

    private static GatewayProperties propertiesWith(Path store) {
        GatewayProperties properties = new GatewayProperties();
        properties.setKeysStorePath(store == null ? "" : store.toString());
        return properties;
    }

    @Test
    void createFindRevokeLifecycle(@TempDir Path tmp) {
        ManagedApiKeyRegistry registry = new ManagedApiKeyRegistry(
                propertiesWith(tmp.resolve("keys.json")), new JsonKeyStore(propertiesWith(tmp.resolve("keys.json"))));
        var created = registry.create("app-a", 5_000, Set.of("gpt-x"), null);
        assertTrue(created.token().startsWith("sk-"));
        assertEquals("app-a", registry.find(created.token()).orElseThrow().name());
        assertEquals(5_000, registry.find(created.token()).orElseThrow().quotaTokens());
        assertTrue(registry.byId(created.key().id()).isPresent());

        // The token never shows up in metadata views.
        assertNotEquals(created.token(), registry.byId(created.key().id()).orElseThrow().toString());

        assertTrue(registry.revoke(created.key().id()));
        assertFalse(registry.revoke(created.key().id()), "double revoke is false");
        assertTrue(registry.find(created.token()).isEmpty());
        assertTrue(registry.byId(created.key().id()).isEmpty());
    }

    @Test
    void ttlExpiryIsEnforcedOnTheReadPath(@TempDir Path tmp) throws Exception {
        JsonKeyStore store = new JsonKeyStore(propertiesWith(tmp.resolve("keys.json")));
        ManagedApiKeyRegistry registry = new ManagedApiKeyRegistry(
                propertiesWith(tmp.resolve("keys.json")), store);
        var created = registry.create("short-lived", 1_000, Set.of(), 1L);
        // expiresAtEpochMs = now + 1s; simulate the passage of time by loading
        // a key whose expiry is already past.
        var fresh = registry.find(created.token());
        assertTrue(fresh.isPresent());
        assertEquals(created.key().expiresAtEpochMs() > 0, fresh.orElseThrow().expired(0) == false);
        var expiredKey = registry.find(created.token()).orElseThrow();
        assertTrue(expiredKey.expired(expiredKey.expiresAtEpochMs() + 1),
                "expiry math: expired(now > expiresAt) is true");
        // Registry-level: a synthetic expired key never resolves.
        registry.register("sk-expired-test",
                new io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry.ApiKey(
                        "k", "k", 10, Set.of(), System.currentTimeMillis() - 1_000));
        assertTrue(registry.find("sk-expired-test").isEmpty());
    }

    @Test
    void runtimeKeysSurviveARestartViaTheStore(@TempDir Path tmp) {
        Path storePath = tmp.resolve("keys.json");
        var props = propertiesWith(storePath);
        ManagedApiKeyRegistry first = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var created = first.create("survivor", 7_000, Set.of("m1"), null);
        assertTrue(java.nio.file.Files.exists(storePath), "store file written on create");

        // "Restart": a brand-new registry over the same store file.
        ManagedApiKeyRegistry second = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var found = second.find(created.token());
        assertTrue(found.isPresent(), "runtime key must survive a restart");
        assertEquals("survivor", found.orElseThrow().name());
        assertEquals(7_000, found.orElseThrow().quotaTokens());

        // Revocation also survives.
        assertTrue(second.revoke(created.key().id()));
        ManagedApiKeyRegistry third = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        assertTrue(third.find(created.token()).isEmpty());
    }

    @Test
    void configKeysStillResolveAlongsideRuntimeKeys(@TempDir Path tmp) {
        GatewayProperties properties = propertiesWith(tmp.resolve("keys.json"));
        GatewayProperties.ApiKeyDef def = new GatewayProperties.ApiKeyDef();
        def.setId("static");
        def.setToken("sk-static");
        def.setName("static");
        def.setQuotaTokens(9_000);
        properties.setApiKeys(java.util.List.of(def));
        ManagedApiKeyRegistry registry = new ManagedApiKeyRegistry(properties,
                new JsonKeyStore(properties));
        assertEquals("static", registry.find("sk-static").orElseThrow().name());
        var created = registry.create("runtime", 1, Set.of(), null);
        assertTrue(registry.find(created.token()).isPresent());
    }

    @Test
    void revocationKillsEveryRotatedTokenAndSurvivesRestart(@TempDir Path tmp) {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var registry = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var original = registry.create("a", 100, Set.of(), null);
        var second = registry.rotate(original.key().id(), 300);
        var third = registry.rotate(original.key().id(), 300);
        assertTrue(registry.revoke(original.key().id()));
        for (String token : java.util.List.of(original.token(), second.token(), third.token())) {
            assertTrue(registry.find(token).isEmpty(), "revoke must invalidate all token generations");
        }
        var restored = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        assertTrue(restored.find(original.token()).isEmpty());
        assertTrue(restored.find(second.token()).isEmpty());
        assertTrue(restored.find(third.token()).isEmpty());
    }

    @Test
    void restartDoesNotReenableExpiredRotationToken(@TempDir Path tmp) {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var registry = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var original = registry.create("a", 100, Set.of(), null);
        var rotated = registry.rotate(original.key().id(), 0);
        assertTrue(registry.find(original.token()).isEmpty());
        var restored = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        assertTrue(restored.find(original.token()).isEmpty());
        assertTrue(restored.find(rotated.token()).isPresent());
        assertEquals(rotated.key().id(), restored.byId(rotated.key().id()).orElseThrow().id());
    }

    @Test
    void restartPreservesOverlapWithoutTreatingLegacyAsCurrent(@TempDir Path tmp) {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var registry = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var original = registry.create("a", 100, Set.of(), null);
        var rotated = registry.rotate(original.key().id(), 300);
        var restored = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        assertTrue(restored.find(original.token()).isPresent());
        assertTrue(restored.find(rotated.token()).isPresent());
        assertTrue(restored.revoke(rotated.key().id()));
        assertTrue(restored.find(original.token()).isEmpty());
        assertTrue(restored.find(rotated.token()).isEmpty());
    }

    @Test
    void newKeyIdentityNeverReusesRestoredIdentity(@TempDir Path tmp) {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var first = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var a = first.create("a", 100, Set.of(), null);
        var restored = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        var b = restored.create("b", 100, Set.of(), null);
        assertNotEquals(a.key().id(), b.key().id());
        assertTrue(restored.revoke(b.key().id()));
        assertTrue(restored.find(a.token()).isPresent());
    }

    @Test
    void failedPersistDoesNotPublishCreatedKey(@TempDir Path tmp) throws Exception {
        Path blocked = tmp.resolve("not-a-directory");
        java.nio.file.Files.writeString(blocked, "block");
        var props = propertiesWith(blocked.resolve("keys.json"));
        var registry = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
        int before = registry.all().size();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> registry.create("a", 100, Set.of(), null));
        assertEquals(before, registry.all().size());
    }

    @Test
    void concurrentStoresDoNotLoseCreatedKeys(@TempDir Path tmp) throws Exception {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var registries = java.util.List.of(
                new ManagedApiKeyRegistry(props, new JsonKeyStore(props)),
                new ManagedApiKeyRegistry(props, new JsonKeyStore(props)));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<KeyAdminService.CreatedKey>>();
            for (int i = 0; i < 64; i++) {
                final int index = i;
                futures.add(executor.submit(() -> registries.get(index % 2).create("app-" + index, 100, Set.of(), null)));
            }
            // Wait for all writes before restoring the actual persisted state.
            var tokens = new java.util.ArrayList<String>();
            for (var future : futures) tokens.add(future.get().token());
            var restored = new ManagedApiKeyRegistry(props, new JsonKeyStore(props));
            for (String token : tokens) assertTrue(restored.find(token).isPresent(), "every successful create must persist");
            assertEquals(64, new JsonKeyStore(props).load().size());
        }
    }

    @Test
    void restoreRejectsAmbiguousCurrentIdentities(@TempDir Path tmp) {
        var props = propertiesWith(tmp.resolve("keys.json"));
        var store = new JsonKeyStore(props);
        var key = new io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry.ApiKey("same-id", "app", 100, Set.of());
        store.upsert("sk-one", key);
        store.upsert("sk-two", key);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ManagedApiKeyRegistry(props, new JsonKeyStore(props)));
    }
}
