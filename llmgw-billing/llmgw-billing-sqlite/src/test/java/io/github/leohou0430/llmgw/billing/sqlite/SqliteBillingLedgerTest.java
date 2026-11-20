package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.ReversalCommand;
import io.github.leohou0430.llmgw.persistence.sqlite.Migration;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0007 billing semantics against SQLite: atomic nano-CNY admission with limited
 * overdraft, settlement merging the per-tenant carry remainder, pending-verification
 * surviving restarts, voucher-idempotent recharges with reversals, and project
 * sub-limits. Restart is simulated by opening a new SqliteContext over the same file.
 */
class SqliteBillingLedgerTest {

	@TempDir
	Path directory;

	private SqliteBillingLedger ledger(Path file) throws Exception {
		try (var db = new SqliteContext(file, 1000)) {
			new SchemaMigrator(db).migrate(allMigrations());
		}
		return new SqliteBillingLedger(new SqliteContext(file, 1000));
	}

	/** V1..V11 基线（含反射加载的 V10 billing pack）。 */
	private static List<Migration> allMigrations() {
		return Migrations.all();
	}

	private static Price price(long in, long out) {
		return new Price(1, "m", in, out, 0);
	}

	@Test
	void admissionReservesAndSettlePostsWithCarryRemainder() throws Exception {
		var ledger = ledger(directory.resolve("b1.db"));
		ledger.setOverdraft("t1", 1_000_000); // 1 CNY overdraft
		var reservation = ledger.reserve("t1", "p1", "m", 1, 2_500);
		assertNotNull(reservation);
		assertEquals(3_000, reservation.amountNano(), "estimate rounds UP to whole micro");

		var frozen = price(1_000_000, 2_000_000); // $1/M in, $2/M out
		var settle = ledger.settle(reservation.id(), 1_000, 500, frozen);
		// raw = 1000*1 + 500*2 = 2000 nano... wait: tokens×nano/M / 1e6
		// = 1000×1000000/1e6 + 500×2000000/1e6 = 1000 + 1000 = 2000 nano
		assertEquals(2_000, settle.postedNano());
		assertEquals(0, settle.carryNano());
		var account = ledger.account("t1").orElseThrow();
		assertEquals(-2_000, account.balanceNano(), "no recharge: balance goes negative (overdraft)");
	}

	@Test
	void admissionDeniesWhenAvailableAmountIsInsufficient() throws Exception {
		var ledger = ledger(directory.resolve("b2.db"));
		ledger.recharge("t1", 1_000_000, "V-INIT", "ops-1"); // 余额 1M nano
		var first = ledger.reserve("t1", "p1", "m", 1, 600_000);
		assertNotNull(first);
		assertNull(ledger.reserve("t1", "p1", "m", 1, 600_000),
				"available (400k after first reserve) cannot cover another 600k");
	}

	@Test
	void pendingVerificationSurvivesRestart() throws Exception {
		Path file = directory.resolve("b3.db");
		var ledger = ledger(file);
		ledger.recharge("t1", 1_000_000, "V-INIT", "ops-1");
		var reservation = ledger.reserve("t1", "p1", "m", 1, 1_000);
		ledger.markPendingVerify(reservation.id(), "upstream connection lost mid-stream");

		// Restart: a brand-new ledger over the same file sees the pending item.
		var restarted = ledger(file);
		var pending = restarted.pendingVerifications("t1");
		assertEquals(1, pending.size());
		assertEquals(reservation.id(), pending.get(0).id());

		// 核实后结算（管理员处理记录原因与操作者）：1500 nano → 入账完整单位
		// 1000 nano（1 micro），尾数 500 按租户保留。
		var settle = restarted.settlePending(reservation.id(), 1_500, "verified with upstream", "ops-1");
		assertEquals(1_000, settle.postedNano());
		assertEquals(500, settle.carryNano());
		assertTrue(restarted.pendingVerifications("t1").isEmpty());
	}

	@Test
	void releaseReturnsTheReservation() throws Exception {
		var ledger = ledger(directory.resolve("b4.db"));
		ledger.setOverdraft("t1", 1_000_000);
		var reservation = ledger.reserve("t1", "p1", "m", 1, 5_000);
		ledger.release(reservation.id(), "caller cancelled", "ops-1");
		// Released amount frees availability: a full-size estimate fits again.
		assertNotNull(ledger.reserve("t1", "p1", "m", 1, 5_000));
	}

	@Test
	void rechargeIsVoucherIdempotent() throws Exception {
		var ledger = ledger(directory.resolve("b5.db"));
		var first = ledger.recharge("t1", 10_000_000, "V-001", "ops-1");
		var duplicate = ledger.recharge("t1", 10_000_000, "V-001", "ops-1");
		assertEquals(10_000_000, first.amountNano());
		assertTrue(first.amountNano() == duplicate.amountNano());
		assertTrue(duplicate.alreadyRecorded(), "duplicate voucher does not double-credit");
		assertEquals(10_000_000, ledger.account("t1").orElseThrow().balanceNano());
	}

	@Test
	void reversalIsANegativeLedgerEntry() throws Exception {
		var ledger = ledger(directory.resolve("b6.db"));
		ledger.recharge("t1", 5_000_000, "V-001", "ops-1");
		var reversal = ledger.recharge("t1", -5_000_000, "R-001", "ops-1");
		assertTrue(reversal.amountNano() < 0, "冲正流水为负金额");
		assertEquals(0, ledger.account("t1").orElseThrow().balanceNano());
	}

	@Test
	void carryRemainderAccumulatesAcrossSettlements() throws Exception {
		var ledger = ledger(directory.resolve("b7.db"));
		ledger.recharge("t1", 10_000_000, "V-001", "ops-1");
		// price 333 nano/M：1_000_001 tokens → raw = floor(1000001*333/1e6 +0.5)
		// = 333 nano（不足 1 micro=1000 nano）→ 尾数按租户累计。
		var frozen = price(333, 0);
		var r1 = ledger.reserve("t1", "p1", "m", 1, 1_000);
		var s1 = ledger.settle(r1.id(), 1_000_001, 0, frozen);
		assertEquals(0, s1.postedNano(), "333 nano 不足入账单位，全部进尾数");
		assertEquals(333, s1.carryNano());
		var r2 = ledger.reserve("t1", "p1", "m", 1, 1_000);
		var s2 = ledger.settle(r2.id(), 1_000_001, 0, frozen);
		assertEquals(0, s2.postedNano());
		assertEquals(666, s2.carryNano(), "尾数跨结算累计");
		var r3 = ledger.reserve("t1", "p1", "m", 1, 1_000);
		var s3 = ledger.settle(r3.id(), 1_000_001, 0, frozen);
		assertEquals(0, s3.postedNano());
		assertEquals(999, s3.carryNano());
		var r4 = ledger.reserve("t1", "p1", "m", 1, 1_000);
		var s4 = ledger.settle(r4.id(), 1_000_001, 0, frozen);
		// 999 + 333 = 1332 nano → 入账 1000 nano（1 micro），剩余尾数 332。
		assertEquals(1_000, s4.postedNano());
		assertEquals(332, s4.carryNano());
		assertEquals(10_000_000 - 1_000, ledger.account("t1").orElseThrow().balanceNano());
	}

	@Test
	void projectSublimitGatesAdmission() throws Exception {
		var ledger = ledger(directory.resolve("b8.db"));
		ledger.setOverdraft("t1", 1_000_000);
		ledger.setProjectSublimit("t1", "p1", 500_000);
		var reservation = ledger.reserve("t1", "p1", "m", 1, 400_000);
		assertNotNull(reservation);
		// 子限额是累计额度：已消费 + 在途预留参与准入；不足则拒绝新调用。
		assertNull(ledger.reserve("t1", "p1", "m", 1, 400_000),
				"project sub-limit (400k reserved of 500k) blocks another 400k");
	}

	@Test
	void overdraftIsMutableByManagement() throws Exception {
		var ledger = ledger(directory.resolve("b9.db"));
		assertNull(ledger.reserve("t1", "p1", "m", 1, 1));
		ledger.setOverdraft("t1", 100_000);
		assertNotNull(ledger.reserve("t1", "p1", "m", 1, 50_000));
		assertEquals(100_000, ledger.account("t1").orElseThrow().overdraftNano());
	}

	@Test
	void reversalReferencesTheOriginalEntryWithIdempotentReplay() throws Exception {
		var ledger = ledger(directory.resolve("b10.db"));
		var entry = ledger.recharge("t1", 1_000_000, "V-001", "ops-1");
		assertEquals(1_000_000, ledger.account("t1").orElseThrow().balanceNano());

		// 冲正引用原流水：整笔负入账。
		var reversal = ledger.reverse(new ReversalCommand(entry.entryId(), "t1", "ops-1", "customer refund", "R-001"));
		assertEquals(-1_000_000, reversal.amountNano());
		assertEquals(0, ledger.account("t1").orElseThrow().balanceNano());

		// 同 operationId 重放 = 幂等命中，不二次扣减。
		var replay = ledger.reverse(new ReversalCommand(entry.entryId(), "t1", "ops-1", "customer refund", "R-001"));
		assertTrue(replay.alreadyRecorded());
		assertEquals(0, ledger.account("t1").orElseThrow().balanceNano());

		// 不同 operationId 再冲正同一原流水 = 拒绝（禁止重复冲正）。
		assertThrows(IllegalStateException.class,
				() -> ledger.reverse(new ReversalCommand(entry.entryId(), "t1", "ops-1", "double reversal", "R-002")));

		// 跨租户引用原流水 = 拒绝。
		assertThrows(IllegalArgumentException.class,
				() -> ledger.reverse(new ReversalCommand(entry.entryId(), "t2", "ops-1", "cross tenant", "R-003")));
	}

}
