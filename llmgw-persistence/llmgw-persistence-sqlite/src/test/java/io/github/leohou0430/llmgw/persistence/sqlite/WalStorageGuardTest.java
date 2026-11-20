package io.github.leohou0430.llmgw.persistence.sqlite;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WalStorageGuardTest {

	@TempDir
	Path folder;

	@Test
	void walCountsTowardCapAndBlocksWholeTransaction() throws Exception {
		Path file = folder.resolve("wal.db");
		try (var db = new SqliteContext(file, 2000)) {
			db.transaction(c -> {
				try (var s = c.createStatement()) {
					s.execute("PRAGMA wal_autocheckpoint=0");
					s.execute("CREATE TABLE x(value BLOB)");
					s.execute("INSERT INTO x VALUES(zeroblob(2097152))");
				}
				return null;
			});
			assertTrue(Files.size(Path.of(file + "-wal")) > 1048576);
			var guard = new StorageGuard(1048576, 0, file);
			db.setWriteGuard(guard::requireWritable);
			assertThrows(StorageLimitException.class, () -> db.transaction(c -> {
				c.createStatement().execute("DELETE FROM x");
				return null;
			}));
			assertEquals(1L, (long) db.read(c -> {
				try (var r = c.createStatement().executeQuery("SELECT count(*) FROM x")) {
					r.next();
					return r.getLong(1);
				}
			}));
		}
	}

}
