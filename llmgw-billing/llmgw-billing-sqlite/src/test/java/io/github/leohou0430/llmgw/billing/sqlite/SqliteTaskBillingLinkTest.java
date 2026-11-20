package io.github.leohou0430.llmgw.billing.sqlite;

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
 * 异步任务计费关联（任务4）：唯一键绑定、跨项目隔离、重复终态 CAS 拒绝、 重启（同文件重开）后状态保留。
 */
class SqliteTaskBillingLinkTest {

	@TempDir
	Path directory;

	private SqliteTaskBillingLink link(Path file) throws Exception {
		try (var db = new SqliteContext(file, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
		}
		return new SqliteTaskBillingLink(new SqliteContext(file, 1000));
	}

	@Test
	void bindIsUniquePerTenantProjectSourceAndTask() throws Exception {
		var link = link(directory.resolve("t1.db"));
		link.bind("t1", "p1", "route-a", "task-1", "res-1", "vid", 1);
		assertThrows(IllegalStateException.class, () -> link.bind("t1", "p1", "route-a", "task-1", "res-2", "vid", 1),
				"同一任务重复绑定（不同 billingCallId）拒绝");
		// 不同租户/项目的同 id 任务互不冲突。
		link.bind("t2", "p1", "route-a", "task-1", "res-3", "vid", 1);
		link.bind("t1", "p2", "route-a", "task-1", "res-4", "vid", 1);
	}

	@Test
	void crossProjectPollCannotReadAnotherProjectsTask() throws Exception {
		var link = link(directory.resolve("t2.db"));
		link.bind("t1", "p1", "route-a", "task-9", "res-1", "vid", 1);
		assertTrue(link.find("t1", "p2", "route-a", "task-9").isEmpty(), "跨项目轮询读不到他人任务");
		assertTrue(link.find("t2", "p1", "route-a", "task-9").isEmpty(), "跨租户轮询读不到他人任务");
		assertEquals("res-1", link.find("t1", "p1", "route-a", "task-9").orElseThrow().billingCallId());
	}

	@Test
	void repeatedTerminalStatesAreRejectedByTheCas() throws Exception {
		var link = link(directory.resolve("t3.db"));
		link.bind("t1", "p1", "route-a", "task-2", "res-1", "vid", 1);
		link.markSettled("t1", "p1", "route-a", "task-2", 500_000);
		assertEquals("settled", link.find("t1", "p1", "route-a", "task-2").orElseThrow().status());
		assertThrows(IllegalStateException.class, () -> link.markSettled("t1", "p1", "route-a", "task-2", 500_000),
				"重复终态（轮询超时后再次完成）拒绝：只结算一次");
		assertThrows(IllegalStateException.class, () -> link.markReleased("t1", "p1", "route-a", "task-2"),
				"settled 不能转 released");
	}

	@Test
	void releasedTerminalIsAlsoFinal() throws Exception {
		var link = link(directory.resolve("t4.db"));
		link.bind("t1", "p1", "route-a", "task-3", "res-1", "vid", 1);
		link.markReleased("t1", "p1", "route-a", "task-3");
		assertThrows(IllegalStateException.class, () -> link.markSettled("t1", "p1", "route-a", "task-3", 1),
				"released 不能转 settled");
	}

	@Test
	void statusSurvivesRestartOnTheSameFile() throws Exception {
		var file = directory.resolve("t5.db");
		var first = link(file);
		first.bind("t1", "p1", "route-a", "task-4", "res-9", "vid", 3);
		// 重启（新 SqliteContext）：pending 保留、可终态。
		var restarted = link(file);
		var pending = restarted.find("t1", "p1", "route-a", "task-4").orElseThrow();
		assertEquals("pending", pending.status());
		assertEquals("res-9", pending.billingCallId());
		assertEquals(3, pending.priceVersion());
		restarted.markSettled("t1", "p1", "route-a", "task-4", 700_000);
		var again = link(file);
		assertEquals("settled", again.find("t1", "p1", "route-a", "task-4").orElseThrow().status());
	}

}
