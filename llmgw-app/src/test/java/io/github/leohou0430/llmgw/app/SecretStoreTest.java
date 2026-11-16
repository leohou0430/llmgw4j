package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.RootKeyMissingException;
import io.github.leohou0430.llmgw.control.local.AesGcmSecretCipher;
import io.github.leohou0430.llmgw.control.local.StaticRootKey;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoreTest {

    private Connection connection;
    private DatabaseContext database;
    private SecretStore store;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE upstream_secret (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL,"
                    + " project_id TEXT NOT NULL, name TEXT NOT NULL, cipher_text TEXT NOT NULL,"
                    + " rotated_from TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        }
        database = new DatabaseContext() {
            @Override
            public Connection connection() {
                return connection;
            }

            @Override
            public void close() {
            }
        };
        store = new SecretStore(database, new AesGcmSecretCipher(new StaticRootKey(new byte[32])));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void storesCiphertextAndNeverPlaintext() throws Exception {
        String id = store.create("t1", "p1", "deepseek", "sk-secret-1234");
        try (Statement q = connection.createStatement();
             var rows = q.executeQuery("SELECT cipher_text FROM upstream_secret WHERE id = '" + id + "'")) {
            String cipher = rows.getString(1);
            assertNotEquals("sk-secret-1234", cipher);
            assertFalse(cipher.contains("sk-secret-1234"));
        }
    }

    @Test
    void listDecryptsToMaskedSuffixOnly() {
        store.create("t1", "p1", "deepseek", "sk-secret-1234");
        store.create("t1", "p2", "other", "sk-other-9999");
        List<SecretStore.SecretSummary> summaries = store.list("t1", "p1");
        assertEquals(1, summaries.size());
        assertEquals("deepseek", summaries.get(0).name());
        assertEquals("****1234", summaries.get(0).masked());
        assertTrue(summaries.get(0).masked().indexOf("secret") < 0);
    }

    @Test
    void rotateReplacesTheCiphertextAndTheNewValueDecrypts() {
        String id = store.create("t1", "p1", "deepseek", "sk-old-1111");
        store.rotate("t1", "p1", id, "sk-new-2222");
        assertEquals("****2222", store.list("t1", "p1").get(0).masked());
    }

    @Test
    void aFreshStoreInstanceReadsTheSameCiphertext() {
        store.create("t1", "p1", "deepseek", "sk-secret-1234");
        SecretStore reopened = new SecretStore(database,
                new AesGcmSecretCipher(new StaticRootKey(new byte[32])));
        assertEquals("****1234", reopened.list("t1", "p1").get(0).masked());
    }

    @Test
    void missingRootKeyFailsClosed() {
        SecretStore closed = new SecretStore(database,
                new AesGcmSecretCipher(StaticRootKey.missing()));
        assertThrows(RootKeyMissingException.class, () -> closed.create("t1", "p1", "x", "sk-plain-0000"));
    }

    @Test
    void writeGuardRejectsTheWriteBeforeAnythingIsStored() {
        SecretStore guarded = new SecretStore(database,
                new AesGcmSecretCipher(new StaticRootKey(new byte[32])),
                () -> {
                    throw new io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException("disk full");
                });
        assertThrows(io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException.class,
                () -> guarded.create("t1", "p1", "x", "sk-plain-0000"));
        assertTrue(guarded.list("t1", "p1").isEmpty(), "nothing may be persisted when the guard rejects");
    }
}
