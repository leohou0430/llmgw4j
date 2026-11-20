package io.github.leohou0430.llmgw.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TransactionIsolationTest {

	@TempDir
	Path folder;

	@Test
	void concurrentSuccessCannotJoinAnotherTransactionsRollback() throws Exception {
		try (var db = new SqliteContext(folder.resolve("data.db"), 2000)) {
			db.transaction(c -> {
				c.createStatement().execute("CREATE TABLE x(id TEXT)");
				return null;
			});
			var started = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var attempting = new CountDownLatch(1);
			var committed = new CountDownLatch(1);
			Thread bad = Thread.ofVirtual().start(() -> assertThrows(SQLException.class, () -> db.transaction(c -> {
				c.createStatement().execute("INSERT INTO x VALUES('bad')");
				started.countDown();
				try {
					assertTrue(release.await(5, TimeUnit.SECONDS));
				}
				catch (InterruptedException e) {
					throw new SQLException(e);
				}
				throw new SQLException("injected");
			})));
			assertTrue(started.await(5, TimeUnit.SECONDS));
			Thread good = Thread.ofVirtual().start(() -> {
				attempting.countDown();
				try {
					db.transaction(c -> {
						c.createStatement().execute("INSERT INTO x VALUES('good')");
						return null;
					});
					committed.countDown();
				}
				catch (SQLException e) {
					throw new RuntimeException(e);
				}
			});
			assertTrue(attempting.await(5, TimeUnit.SECONDS));
			assertEquals(1, committed.getCount());
			release.countDown();
			bad.join();
			good.join();
			assertEquals(0, committed.getCount());
			assertEquals("good", db.read(c -> {
				try (var rs = c.createStatement().executeQuery("SELECT id FROM x")) {
					assertTrue(rs.next());
					String id = rs.getString(1);
					assertFalse(rs.next());
					return id;
				}
			}));
		}
	}

	@Test
	void caughtNestedFailureStillRollsBackOuterChange() throws Exception {
		try (var db = new SqliteContext(folder.resolve("nested.db"), 2000)) {
			db.transaction(c -> {
				c.createStatement().execute("CREATE TABLE x(id TEXT)");
				return null;
			});
			assertThrows(SQLException.class, () -> db.transaction(c -> {
				c.createStatement().execute("INSERT INTO x VALUES('outer')");
				try {
					db.transaction(inner -> {
						throw new IllegalArgumentException("nested");
					});
				}
				catch (IllegalArgumentException expected) {
				}
				return null;
			}));
			assertEquals(0L, (long) db.read(c -> {
				try (var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM x")) {
					rs.next();
					return rs.getLong(1);
				}
			}));
		}
	}

}
