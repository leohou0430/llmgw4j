package io.github.leohou0430.llmgw.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务9 备份清单合同：摘要一致放行；缺失/篡改清单保持关闸。 */
class OnlineBackupManifestTest {

	@TempDir
	Path directory;

	@Test
	void manifestVerificationPassesForAnIntactBackup() throws Exception {
		Path source = directory.resolve("source.db");
		try (SqliteContext db = new SqliteContext(source, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		Path backup = directory.resolve("backup.db");
		Path manifest = OnlineBackup.backupWithManifest(new SqliteContext(source, 1000), backup);
		assertTrue(Files.exists(manifest));
		assertTrue(Files.size(backup) > 0);
		assertDoesNotThrow(() -> OnlineBackup.verifyManifest(backup));
	}

	@Test
	void missingManifestKeepsTheRestoreGateClosed() {
		Path backup = directory.resolve("no-manifest.db");
		try {
			Files.write(backup, new byte[] { 1, 2, 3 });
		}
		catch (Exception ignored) {
			// 内容无所谓：清单缺失即拒绝
		}
		assertThrows(IllegalStateException.class, () -> OnlineBackup.verifyManifest(backup), "清单缺失：恢复闸门保持关闭");
	}

	@Test
	void tamperedBackupFailsTheDigestGate() throws Exception {
		Path source = directory.resolve("source2.db");
		try (SqliteContext db = new SqliteContext(source, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		Path backup = directory.resolve("backup2.db");
		OnlineBackup.backupWithManifest(new SqliteContext(source, 1000), backup);
		// 篡改备份字节（摘要不再匹配清单）。
		Files.write(backup, new byte[] { 9, 9, 9, 9, 9 });
		assertThrows(IllegalStateException.class, () -> OnlineBackup.verifyManifest(backup), "摘要不匹配：恢复闸门保持关闭");
	}

}
