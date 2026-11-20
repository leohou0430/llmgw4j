package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.RootKeyMismatchException;
import io.github.leohou0430.llmgw.control.RootKeyMissingException;
import io.github.leohou0430.llmgw.control.local.AesGcmSecretCipher;
import io.github.leohou0430.llmgw.control.local.StaticRootKey;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.OnlineBackup;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretStoreBackupRecoveryTest {

	private static final String PLAINTEXT = "sk-live-abcdef123456789";

	@TempDir
	Path tempDir;

	@Test
	void onlineBackupRestoresSecretsOnlyWithTheSameRootKey() throws Exception {
		byte[] rootKey = randomKey();
		Path databaseFile = tempDir.resolve("control.db");
		Path backupFile = tempDir.resolve("control-backup.db");

		try (SqliteContext source = migrate(databaseFile)) {
			new SecretStore(source, cipher(rootKey)).create("t1", "p1", "openai", PLAINTEXT);
			assertFalse(readCipherText(source).contains(PLAINTEXT));
			OnlineBackup.backup(source, backupFile);
		}

		try (SqliteContext restored = new SqliteContext(backupFile, 1000L)) {
			assertFalse(readCipherText(restored).contains(PLAINTEXT));
			List<SecretStore.SecretSummary> sameKey = new SecretStore(restored, cipher(rootKey)).list("t1", "p1");
			assertEquals(1, sameKey.size());
			assertEquals("****6789", sameKey.get(0).masked());
		}

		try (SqliteContext restored = new SqliteContext(backupFile, 1000L)) {
			SecretStore wrongKey = new SecretStore(restored, cipher(randomKey()));
			assertThrows(RootKeyMismatchException.class, () -> wrongKey.list("t1", "p1"));
			assertThrows(RootKeyMismatchException.class, () -> wrongKey.create("t1", "p1", "wrong", PLAINTEXT));
			assertEquals(1, countSecrets(restored));
		}

		try (SqliteContext restored = new SqliteContext(backupFile, 1000L)) {
			SecretStore missingKey = new SecretStore(restored, new AesGcmSecretCipher(StaticRootKey.missing()));
			assertThrows(RootKeyMissingException.class, () -> missingKey.list("t1", "p1"));
			assertThrows(RootKeyMissingException.class, () -> missingKey.create("t1", "p1", "other", PLAINTEXT));
			assertEquals(1, countSecrets(restored));
		}
	}

	private SqliteContext migrate(Path file) throws Exception {
		SqliteContext context = new SqliteContext(file, 1000L);
		new SchemaMigrator(context).migrate(Migrations.all());
		return context;
	}

	private static AesGcmSecretCipher cipher(byte[] key) {
		return new AesGcmSecretCipher(new StaticRootKey(key));
	}

	private static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private static String readCipherText(SqliteContext context) throws Exception {
		try (Statement statement = context.connection().createStatement();
				ResultSet result = statement.executeQuery("SELECT cipher_text FROM upstream_secret")) {
			return result.next() ? result.getString(1) : "";
		}
	}

	private static int countSecrets(SqliteContext context) throws Exception {
		try (Statement statement = context.connection().createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM upstream_secret")) {
			return result.next() ? result.getInt(1) : 0;
		}
	}

}
