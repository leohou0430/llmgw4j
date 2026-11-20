package io.github.leohou0430.llmgw.idempotency.sqlite;

import io.github.leohou0430.llmgw.idempotency.IdempotencyStore;
import io.github.leohou0430.llmgw.persistence.sqlite.Migration;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONTEXT.md「客户幂等键」与 ADR-0007 对 SQLite 的合同：scope 约束、同键同 摘要重入只读原标识（不重放 SSE）、同键异摘要 409
 * 语义（MISMATCH）、结果 未知不自动重发、推进令牌守卫（迟到终态回调不能结算被 TTL 替换的新调用）、 终态幂等、TTL 过期后完成态行可回收为 FRESH。
 */
class SqliteIdempotencyStoreTest {

	@TempDir
	Path directory;

	private IdempotencyStore store(Path file) throws Exception {
		try (var db = new SqliteContext(file, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		return new SqliteIdempotencyStore(new SqliteContext(file, 1000));
	}

	@Test
	void firstBeginIsFreshThenReentryReturnsOriginalIdentity() throws Exception {
		var store = store(directory.resolve("i1.db"));
		var first = store.begin("p1", "key-1", "digest-A", 60_000, 1_000);
		assertEquals(IdempotencyStore.Status.FRESH, first.status());
		assertNull(first.billingCallId());
		assertNotNull(first.beginToken(), "FRESH carries the one-time completion token");
		// 在途重入：观察者判读，不重放、不占位、无推进权。
		var reentry = store.begin("p1", "key-1", "digest-A", 60_000, 2_000);
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT, reentry.status());
		assertNull(reentry.beginToken());
		// 原请求持令牌推进终态；重入带原标识（调用方可查原结果，不重新执行）。
		store.complete("p1", "key-1", "digest-A", first.beginToken(), "call-9", "req-9", true, 3_000);
		var completed = store.begin("p1", "key-1", "digest-A", 60_000, 4_000);
		assertEquals(IdempotencyStore.Status.EXISTING_COMPLETED, completed.status());
		assertEquals("call-9", completed.billingCallId());
		assertEquals("req-9", completed.requestId());
	}

	@Test
	void observerWithoutTokenCannotComplete() throws Exception {
		var store = store(directory.resolve("i2.db"));
		store.begin("p1", "key-1", "digest-A", 60_000, 1_000);
		// 无令牌/错令牌的推进是迟到回调：一律 no-op，不产生终态。
		store.complete("p1", "key-1", "digest-A", null, "call-x", "req-x", true, 2_000);
		store.complete("p1", "key-1", "digest-A", "idt-forged", "call-x", "req-x", true, 3_000);
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT,
				store.begin("p1", "key-1", "digest-A", 60_000, 4_000).status());
	}

	@Test
	void sameKeyDifferentDigestIsMismatch() throws Exception {
		var store = store(directory.resolve("i3.db"));
		store.begin("p1", "key-1", "digest-A", 60_000, 1_000);
		var mismatch = store.begin("p1", "key-1", "digest-B", 60_000, 2_000);
		assertEquals(IdempotencyStore.Status.MISMATCH, mismatch.status());
	}

	@Test
	void scopesNeverSeeEachOthersKeys() throws Exception {
		var store = store(directory.resolve("i4.db"));
		assertEquals(IdempotencyStore.Status.FRESH, store.begin("p1", "key-1", "digest-A", 60_000, 1_000).status());
		// 相同幂等键在不同 scope（项目）是相互独立的调用。
		assertEquals(IdempotencyStore.Status.FRESH, store.begin("p2", "key-1", "digest-A", 60_000, 1_000).status());
	}

	@Test
	void unknownOutcomeRetainsProtectionAndNeverAutoReplays() throws Exception {
		var store = store(directory.resolve("i5.db"));
		var first = store.begin("p1", "key-1", "digest-A", 60_000, 1_000);
		// 结果未知：状态落 unknown（不自动重发，交由业务查询/对账）。
		store.complete("p1", "key-1", "digest-A", first.beginToken(), "call-9", "req-9", false, 2_000);
		var unknown = store.begin("p1", "key-1", "digest-A", 60_000, 3_000);
		assertEquals(IdempotencyStore.Status.EXISTING_UNKNOWN, unknown.status());
		// unknown 是吸收终态：后续 completed 推进不改写。
		store.complete("p1", "key-1", "digest-A", first.beginToken(), "call-9", "req-9", true, 4_000);
		assertEquals(IdempotencyStore.Status.EXISTING_UNKNOWN,
				store.begin("p1", "key-1", "digest-A", 60_000, 5_000).status());
	}

	@Test
	void completedRowsExpireAfterTtlAndBecomeFreshAgain() throws Exception {
		var store = store(directory.resolve("i6.db"));
		var first = store.begin("p1", "key-1", "digest-A", 10_000, 1_000);
		store.complete("p1", "key-1", "digest-A", first.beginToken(), "call-9", "req-9", true, 2_000);
		// TTL 窗口内（expires_at=11_000，严格早于 11_000 视为未过期）命中原调用。
		assertEquals(IdempotencyStore.Status.EXISTING_COMPLETED,
				store.begin("p1", "key-1", "digest-A", 10_000, 10_999).status());
		// 过期后完成态行回收，键可再次作为新调用（FRESH 持新令牌）。
		var fresh = store.begin("p1", "key-1", "digest-A", 10_000, 11_001);
		assertEquals(IdempotencyStore.Status.FRESH, fresh.status());
		assertNotNull(fresh.beginToken());
	}

	@Test
	void inFlightRowsSurviveTheirTtlAsProtection() throws Exception {
		var store = store(directory.resolve("i7.db"));
		store.begin("p1", "key-1", "digest-A", 10_000, 1_000);
		// 在途行不因 TTL 失去防重保护（结果未知不能靠过期重发）。
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT,
				store.begin("p1", "key-1", "digest-A", 10_000, 99_000).status());
	}

	@Test
	void staleTerminalCallbackCannotSettleAReplacedCall() throws Exception {
		var store = store(directory.resolve("i8.db"));
		var old = store.begin("p1", "key-1", "digest-A", 1_000, 1_000);
		store.complete("p1", "key-1", "digest-A", old.beginToken(), "call-old", "req-old", true, 2_000);
		// TTL 过期后同键同摘要被新调用占位（新令牌）。
		var fresh = store.begin("p1", "key-1", "digest-A", 60_000, 30_000);
		assertEquals(IdempotencyStore.Status.FRESH, fresh.status());
		// 旧终态回调（旧令牌）迟到：摘要相同也不能结算新占位。
		store.complete("p1", "key-1", "digest-A", old.beginToken(), "call-stale", "req-stale", true, 31_000);
		var reentry = store.begin("p1", "key-1", "digest-A", 60_000, 32_000);
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT, reentry.status());
		assertNull(reentry.billingCallId(), "stale callback must not attach its identity");
		// 新请求持新令牌正常推进。
		store.complete("p1", "key-1", "digest-A", fresh.beginToken(), "call-new", "req-new", true, 33_000);
		assertEquals("call-new", store.begin("p1", "key-1", "digest-A", 60_000, 34_000).billingCallId());
	}

	@Test
	void concurrentBeginYieldsExactlyOneFresh() throws Exception {
		var store = store(directory.resolve("i9.db"));
		AtomicReference<IdempotencyStore.BeginResult> winner = new AtomicReference<>();
		Thread a = new Thread(() -> winner.set(store.begin("p1", "key-1", "digest-A", 60_000, 1_000)));
		Thread b = new Thread(() -> store.begin("p1", "key-1", "digest-A", 60_000, 1_000));
		a.start();
		b.start();
		a.join();
		b.join();
		assertEquals(IdempotencyStore.Status.FRESH, winner.get().status());
		// 并发后必有一个 FRESH 一个 EXISTING_INFLIGHT，绝不两个 FRESH。
		var after = store.begin("p1", "key-1", "digest-A", 60_000, 2_000);
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT, after.status());
	}

	@Test
	void purgeExpiredRemovesOnlyCompletedRows() throws Exception {
		var store = store(directory.resolve("i10.db"));
		var first = store.begin("p1", "done", "d", 1_000, 1_000);
		store.complete("p1", "done", "d", first.beginToken(), "c", "r", true, 2_000);
		store.begin("p1", "live", "d", 60_000, 1_000);
		int purged = store.purgeExpired(60_000);
		assertEquals(1, purged);
		assertEquals(IdempotencyStore.Status.EXISTING_INFLIGHT,
				store.begin("p1", "live", "d", 60_000, 61_000).status());
	}

	@Test
	void blankArgumentsFailFast() throws Exception {
		var store = store(directory.resolve("i11.db"));
		assertThrows(IllegalArgumentException.class, () -> store.begin("", "k", "d", 1_000, 1));
		assertThrows(IllegalArgumentException.class, () -> store.begin("p1", "k", "d", 0, 1));
	}

	@Test
	void schemaSurvivesRestartOnSameDatabaseFile() throws Exception {
		Path file = directory.resolve("i12.db");
		var first = store(file);
		var begin = first.begin("p1", "key-1", "digest-A", 60_000, 1_000);
		first.complete("p1", "key-1", "digest-A", begin.beginToken(), "call-9", "req-9", true, 2_000);
		// 重启（同文件新上下文）：终态与原标识持久保留。
		var reopened = store(file);
		var snapshot = reopened.begin("p1", "key-1", "digest-A", 60_000, 3_000);
		assertEquals(IdempotencyStore.Status.EXISTING_COMPLETED, snapshot.status());
		assertEquals("call-9", snapshot.billingCallId());
		assertTrue(snapshot.beginToken() == null);
	}

}
