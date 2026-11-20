package io.github.leohou0430.llmgw.approval.sqlite;

import io.github.leohou0430.llmgw.approval.ApprovalService;
import io.github.leohou0430.llmgw.persistence.sqlite.Migration;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chapter-9 §9.5.4 审批状态机对 SQLite 的合同：持久 CAS 决策（并发/重复回调 只成功一次）、批准 ≠ 放行（revalidate
 * 校验主体与参数摘要）、超时转 EXPIRED 且不降级为只读、拒绝不隐式替换工具/参数、撤销后一律不放行。
 */
class SqliteApprovalServiceTest {

	@TempDir
	Path directory;

	private ApprovalService service(Path file) throws Exception {
		try (var db = new SqliteContext(file, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		return new SqliteApprovalService(new SqliteContext(file, 1000));
	}

	@Test
	void submitCreatesPendingAndPendingListsPerTenant() throws Exception {
		var approvals = service(directory.resolve("a1.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "refund", "1.2.0", "digest-9", "high",
				60_000, 1_000);
		assertNotNull(approval.id());
		assertEquals(ApprovalService.State.PENDING, approval.state());
		assertEquals(61_000, approval.expiresAtEpochMs());
		assertEquals(1, approvals.pending("t1").size());
		assertTrue(approvals.pending("t2").isEmpty());
	}

	@Test
	void decideApprovesAndRevalidateGatesExecution() throws Exception {
		var approvals = service(directory.resolve("a2.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "refund", "1.2.0", "digest-9", "high",
				60_000, 1_000);
		var decided = approvals.decide(approval.id(), true, "admin-7", "customer complaint", 2_000);
		assertEquals(ApprovalService.State.APPROVED, decided.state());
		assertEquals("admin-7", decided.decidedBy());
		// 批准 ≠ 放行：同主体同摘要才放行。
		assertTrue(approvals.revalidate(approval.id(), "agent-1", "digest-9", 3_000));
		// 异摘要 / 异主体 / 未决不匹配一律 false。
		assertFalse(approvals.revalidate(approval.id(), "agent-1", "digest-X", 3_000));
		assertFalse(approvals.revalidate(approval.id(), "agent-2", "digest-9", 3_000));
		assertFalse(approvals.revalidate("apr-missing", "agent-1", "digest-9", 3_000));
	}

	@Test
	void repeatedDecideIsIdempotentAndNeverFlipsOutcome() throws Exception {
		var approvals = service(directory.resolve("a3.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "refund", "1.2.0", "d", "high", 60_000,
				1_000);
		approvals.decide(approval.id(), false, "admin-1", "out of scope", 2_000);
		var again = approvals.decide(approval.id(), true, "admin-2", "changed mind", 3_000);
		assertEquals(ApprovalService.State.REJECTED, again.state());
		assertEquals("admin-1", again.decidedBy());
	}

	@Test
	void expiredApprovalCannotBeDecidedAndDoesNotDegradeToReadOnly() throws Exception {
		var approvals = service(directory.resolve("a4.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "cluster_restart", "1.0.0", "d", "high",
				5_000, 1_000);
		var expired = approvals.decide(approval.id(), true, "admin-1", "late", 9_000);
		assertEquals(ApprovalService.State.EXPIRED, expired.state());
		// 审批超时不自动降级为只读：revalidate 直接 false，绝不给出放行变体。
		assertFalse(approvals.revalidate(approval.id(), "agent-1", "d", 9_000));
	}

	@Test
	void revokedApprovalStopsAllExecution() throws Exception {
		var approvals = service(directory.resolve("a5.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "refund", "1.2.0", "d", "high", 60_000,
				1_000);
		approvals.decide(approval.id(), true, "admin-1", "ok", 2_000);
		assertTrue(approvals.revalidate(approval.id(), "agent-1", "d", 3_000));
		var revoked = approvals.revoke(approval.id(), "admin-2", "scope withdrawn", 4_000);
		assertEquals(ApprovalService.State.REVOKED, revoked.state());
		assertFalse(approvals.revalidate(approval.id(), "agent-1", "d", 5_000));
		// 已 REVOKED 重复撤销幂等返回原快照；PENDING 状态撤销为 no-op。
		var again = approvals.revoke(approval.id(), "admin-3", "again", 6_000);
		assertEquals(ApprovalService.State.REVOKED, again.state());
		assertEquals("admin-2", again.decidedBy());
	}

	@Test
	void concurrentDecideCasAllowsOnlyOneTerminal() throws Exception {
		var approvals = service(directory.resolve("a6.db"));
		var approval = approvals.submit("agent-1", "t1", "p1", "mcp-tool", "payment", "1.0.0", "d", "high", 60_000,
				1_000);
		// 并发决策：两个线程同时 decide，状态机保证只有一个终态达成。
		Thread approveThread = new Thread(() -> approvals.decide(approval.id(), true, "admin-a", "approve", 2_000));
		Thread rejectThread = new Thread(() -> approvals.decide(approval.id(), false, "admin-b", "reject", 2_000));
		approveThread.start();
		rejectThread.start();
		approveThread.join();
		rejectThread.join();
		var final_ = approvals.get(approval.id()).orElseThrow();
		assertTrue(
				final_.state() == ApprovalService.State.APPROVED || final_.state() == ApprovalService.State.REJECTED);
	}

	@Test
	void unknownApprovalAndBlankArgumentsFailFast() throws Exception {
		var approvals = service(directory.resolve("a7.db"));
		assertThrows(NoSuchElementException.class, () -> approvals.decide("apr-missing", true, "admin", "r", 1));
		assertThrows(IllegalArgumentException.class,
				() -> approvals.submit("", "t", "p", "mcp-tool", "n", "v", "d", "high", 1_000, 1));
		assertThrows(IllegalArgumentException.class,
				() -> approvals.submit("s", "t", "p", "mcp-tool", "n", "v", "d", "high", 0, 1));
	}

	@Test
	void approvalSchemaSurvivesRestartOnSameDatabaseFile() throws Exception {
		Path file = directory.resolve("a8.db");
		var first = service(file);
		var approval = first.submit("agent-1", "t1", "p1", "mcp-tool", "refund", "1.2.0", "d", "high", 60_000, 1_000);
		first.decide(approval.id(), true, "admin-1", "ok", 2_000);
		// 重启（同文件新上下文）：状态与判定持久保留。
		var reopened = service(file);
		var snapshot = reopened.get(approval.id()).orElseThrow();
		assertEquals(ApprovalService.State.APPROVED, snapshot.state());
		assertTrue(reopened.revalidate(approval.id(), "agent-1", "d", 3_000));
	}

}
