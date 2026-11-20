package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务9 归档合同：归档前后余额/累计不变、归档前 operationId 重放仍拒绝、 活动/未知记录不能归档、损坏归档核对拒绝、高水位持久。
 */
class BillingArchiveServiceTest {

	@TempDir
	Path directory;

	private SqliteBillingLedger ledger(Path file) throws Exception {
		try (var db = new SqliteContext(file, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		return new SqliteBillingLedger(new SqliteContext(file, 1000));
	}

	private BillingArchiveService archive(Path file) throws java.sql.SQLException {
		return new BillingArchiveService(new SqliteContext(file, 1000));
	}

	@Test
	void archiveMovesOnlyTerminalRowsAndKeepsAccountsUntouched() throws Exception {
		var file = directory.resolve("a1.db");
		var ledger = ledger(file);
		var archive = archive(file);
		ledger.recharge("t1", 10_000_000, "V-1", "ops");
		ledger.setOverdraft("t1", 1_000_000);
		// 三个终态预留（旧）+ 一个活动 + 一个未知（新）。
		var r1 = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.settle(r1.id(), 1, 1, frozen());
		var r2 = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.release(r2.id(), "caller cancelled", "ops");
		var r3 = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.settle(r3.id(), 1, 1, frozen());
		var active = ledger.reserve("t1", "p1", "m", 1, 2_000);
		var unknown = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.markPendingVerify(unknown.id(), "mid-stream failure");

		var accountBefore = ledger.account("t1").orElseThrow();
		var reservedBefore = ledger.reservedNano("t1");

		// 归档截止：远的终态行；把已结算行的 settled_at 视作"当前-1"由测试直接挪钟不现实，
		// 这里用远期阈值（包含全部现有终态行）验证合同语义。
		var result = archive.archiveBefore(Long.MAX_VALUE / 2);

		assertEquals(3, result.archived(), "恰三个终态预留被归档");
		// 归档前后余额/累计/在途不变：归档只迁移终态行。
		assertEquals(accountBefore, ledger.account("t1").orElseThrow());
		assertEquals(reservedBefore, ledger.reservedNano("t1"));
		// 活动/未知记录不能归档：仍在主表。
		assertTrue(ledger.pendingVerifications("t1").stream().anyMatch(r -> r.id().equals(unknown.id())), "未知预留跨归档保留");
		// 活动预留仍可结算（终态推进不受归档影响）。
		var settled = ledger.settle(active.id(), 1, 1, frozen());
		assertEquals(0, settled.postedNano() % 1000);
		assertTrue(archive.watermark().isPresent());
		assertEquals(3, archive.watermark().orElseThrow().archivedCount());
	}

	@Test
	void rechargeVoucherReplayStaysRejectedAfterArchive() throws Exception {
		var file = directory.resolve("a2.db");
		var ledger = ledger(file);
		var archive = archive(file);
		var first = ledger.recharge("t1", 5_000_000, "V-REPLAY", "ops");
		assertEquals(5_000_000, first.amountNano());
		archive.archiveBefore(Long.MAX_VALUE / 2);
		// 归档不触碰幂等索引：同凭证重放仍返回首次结果，不二次入账。
		var replay = ledger.recharge("t1", 5_000_000, "V-REPLAY", "ops");
		assertTrue(replay.alreadyRecorded(), "归档后凭证重放仍幂等拒绝");
		assertEquals(5_000_000, replay.amountNano());
		assertEquals(5_000_000, ledger.account("t1").orElseThrow().balanceNano());
	}

	@Test
	void tamperedArchiveFailsTheDigestVerification() throws Exception {
		var file = directory.resolve("a3.db");
		var ledger = ledger(file);
		var archive = archive(file);
		ledger.recharge("t1", 1_000_000, "V-1", "ops");
		var r = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.settle(r.id(), 1, 1, frozen());
		archive.archiveBefore(Long.MAX_VALUE / 2);
		archive.verifyDigest(); // 完整归档通过
		// 篡改归档行（直接改归档表金额）。
		try (var db = new SqliteContext(file, 1000)) {
			db.transaction(conn -> {
				try (var update = conn
					.prepareStatement("UPDATE billing_reservation_archive SET amount_nano = 999999999")) {
					update.executeUpdate();
				}
				return null;
			});
		}
		assertThrows(IllegalStateException.class, archive::verifyDigest, "损坏归档恢复核对拒绝");
	}

	@Test
	void watermarkIsPersistentAndIncrementalArchivesAdvanceIt() throws Exception {
		var file = directory.resolve("a4.db");
		var ledger = ledger(file);
		var archive = archive(file);
		assertTrue(archive.watermark().isEmpty(), "未归档时无高水位");
		ledger.recharge("t1", 10_000_000, "V-1", "ops");
		var r1 = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.settle(r1.id(), 1, 1, frozen());
		var first = archive.archiveBefore(Long.MAX_VALUE / 2);
		assertEquals(1, first.archived());
		var mark1 = archive.watermark().orElseThrow();
		// 同文件重开（重启模拟）：高水位持久。
		var reopened = archive(file);
		assertEquals(mark1, reopened.watermark().orElseThrow());
		// 新终态行 → 增量归档推进计数。
		var r2 = ledger.reserve("t1", "p1", "m", 1, 2_000);
		ledger.settle(r2.id(), 1, 1, frozen());
		var second = reopened.archiveBefore(Long.MAX_VALUE / 2);
		assertEquals(1, second.archived());
		assertEquals(2, reopened.watermark().orElseThrow().archivedCount());
	}

	private static io.github.leohou0430.llmgw.billing.Price frozen() {
		return new io.github.leohou0430.llmgw.billing.Price(1, "m", 1_000_000, 2_000_000, 0);
	}

}
