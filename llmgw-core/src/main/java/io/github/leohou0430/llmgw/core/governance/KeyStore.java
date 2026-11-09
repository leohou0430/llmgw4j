package io.github.leohou0430.llmgw.core.governance;

import java.util.Map;

/**
 * B1 key-store SPI: persistence for runtime-created API keys. Implementations
 * decide the at-rest representation — the JSON store hashes tokens when
 * {@code gateway.auth.hash-keys-at-rest} is on; a SQLite/JDBC store slots in
 * behind the same interface (B12). Keys passed in are PLAINTEXT tokens; the
 * implementation owns their at-rest form.
 */
public interface KeyStore {

    /** Restored entry: the key plus rotation linkage (when this token was
     *  rotated out but is still inside its overlap window). */
    record StoredKey(ApiKeyRegistry.ApiKey key, String rotatedTo, long legacyUntil) {
    }

    void upsert(String token, ApiKeyRegistry.ApiKey key);

    void delete(String token);

    /** B1: mark the old token as rotated to the new one, valid until overlapEnd. */
    void rotate(String oldToken, String newToken, long overlapEnd);

    /** B1: rotation recorded by AT-REST keys (used when plaintext is not held). */
    void rotateAtRest(String oldAtRest, String newAtRest, long overlapEnd);

    /** B1: delete by the AT-REST key (used when the plaintext is no longer held). */
    default void deleteAtRest(String atRestKey) {
    }

    /** store-key (hash or plaintext, per implementation) -> entry. */
    Map<String, StoredKey> load();

    boolean enabled();
}
