package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.billing.BillingAccount;
import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.ReversalCommand;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.local.HmacCsrfTokenService;
import io.github.leohou0430.llmgw.identity.local.InMemoryAccountStore;
import io.github.leohou0430.llmgw.identity.local.InMemorySessionStore;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务6 权限合同（四角色×账务动作）：账务写仅平台管理员；scope 内读对租户/ 项目角色开放；无效会话一律 401。控制器方法直接调用（Spring 注解仅 HTTP
 * 层）。
 */
class ControlBillingAccessTest {

	private static RequestAuthorizer authorizer() {
		return new RequestAuthorizer(new InMemorySessionStore(), HmacCsrfTokenService.randomKey(),
				new InMemoryAccountStore(), new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService(),
				System::currentTimeMillis);
	}

	/** ObjectProvider 的固定值实现（组件级测试注入）。 */
	private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public T getObject() {
				return value;
			}
		};
	}

	/** 审计库：迁移一次（audit_event 在 V1 foundational schema）。 */
	private static AuditLog auditDb() throws Exception {
		var dbPath = Path.of(UUID.randomUUID() + ".db");
		try (var db = new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(dbPath, 1000)) {
			new io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator(db)
				.migrate(io.github.leohou0430.llmgw.persistence.sqlite.Migrations.all());
		}
		return new AuditLog(new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(dbPath, 1000));
	}

	@Test
	void platformAdminRechargesAndTenantRolesCannotWrite() throws Exception {
		var sessions = new InMemorySessionStore();
		var accounts = new InMemoryAccountStore();
		var authorizer = new RequestAuthorizer(sessions, HmacCsrfTokenService.randomKey(), accounts,
				new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService(), System::currentTimeMillis);
		var platform = accounts
			.create(new Account("pa-1", "platform", "hash", Role.PLATFORM_ADMIN, new Scope("", ""), true));
		var tenantAdmin = accounts
			.create(new Account("ta-1", "tenant-admin", "hash", Role.TENANT_ADMIN, new Scope("t-smoke", ""), true));
		var platformSid = sessions.create("pa-1", 3_600_000, System.currentTimeMillis()).id();
		var tenantSid = sessions.create("ta-1", 3_600_000, System.currentTimeMillis()).id();

		var ledger = new RecordingLedgerStub();
		var controller = new ControlBillingController(providerOf(ledger), authorizer, auditDb());

		// 平台管理员充值成功（幂等返回首次入账）。
		var result = controller.recharge(Map.of("tenantId", "t-smoke", "amountNano", "5000000", "voucherNo", "V-1"),
				platformSid);
		assertEquals(5_000_000L, result.get("amountNano"));
		assertEquals(1, ledger.events.stream().filter(e -> e.startsWith("recharge")).count());

		// 同凭证重放：幂等命中。
		var replay = controller.recharge(Map.of("tenantId", "t-smoke", "amountNano", "5000000", "voucherNo", "V-1"),
				platformSid);
		assertEquals(Boolean.TRUE, replay.get("alreadyRecorded"));

		// 租户管理员：账务写 403（BILLING_MANAGE 平台专属）。
		var ex = assertThrows(ResponseStatusException.class, () -> controller
			.recharge(Map.of("tenantId", "t-smoke", "amountNano", "1", "voucherNo", "V-2"), tenantSid));
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

		// 租户管理员 scope 内读成功；越权租户读 403。
		var view = controller.account("t-smoke", tenantSid);
		assertEquals("t-smoke", view.get("tenantId"));
		var crossEx = assertThrows(ResponseStatusException.class, () -> controller.account("other-tenant", tenantSid));
		assertEquals(HttpStatus.FORBIDDEN, crossEx.getStatusCode());

		// 无效会话：一律 401。
		var unauth = assertThrows(ResponseStatusException.class, () -> controller.account("t-smoke", "bogus-sid"));
		assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());
	}

	@Test
	void pricePublishIsPlatformAdminOnly() throws Exception {
		var sessions = new InMemorySessionStore();
		var accounts = new InMemoryAccountStore();
		var authorizer = new RequestAuthorizer(sessions, HmacCsrfTokenService.randomKey(), accounts,
				new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService(), System::currentTimeMillis);
		accounts.create(new Account("pa-1", "platform", "hash", Role.PLATFORM_ADMIN, new Scope("", ""), true));
		accounts.create(new Account("ta-1", "tenant-admin", "hash", Role.TENANT_ADMIN, new Scope("t1", ""), true));
		var platformSid = sessions.create("pa-1", 3_600_000, System.currentTimeMillis()).id();
		var tenantSid = sessions.create("ta-1", 3_600_000, System.currentTimeMillis()).id();
		var priceDb = Path.of(UUID.randomUUID() + ".db");
		try (var db = new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(priceDb, 1000)) {
			new io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator(db)
				.migrate(io.github.leohou0430.llmgw.persistence.sqlite.Migrations.all());
		}
		var controller = new ControlPriceController(
				providerOf(new io.github.leohou0430.llmgw.billing.sqlite.SqlitePriceBook(
						new io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext(priceDb, 1000))),
				authorizer, auditDb());

		assertThrows(ResponseStatusException.class,
				() -> controller.publish("m", Map.of("inputNanoPerMillion", "1000000"), tenantSid), "价格发布仅平台管理员");
		var published = controller.publish("m",
				Map.of("inputNanoPerMillion", "1000000", "outputNanoPerMillion", "2000000"), platformSid);
		assertEquals(1L, published.get("version"));
		var versions = controller.versions("m", platformSid);
		assertEquals(1, versions.size());
	}

	/** 记录账本调用的 stub（组件级 wiring 验证）。 */
	private static final class RecordingLedgerStub implements BillingLedger {

		final List<String> events = new java.util.ArrayList<>();

		final java.util.Set<String> seen = new java.util.HashSet<>();

		long balance;

		@Override
		public Reservation reserve(String tenantId, String projectId, String model, long priceVersion,
				long estimateNano) {
			return null;
		}

		@Override
		public SettleResult settle(String reservationId, long actualInputTokens, long actualOutputTokens,
				Price frozen) {
			return new SettleResult(reservationId, 0, 0);
		}

		@Override
		public SettleResult settleNano(String reservationId, long actualNano) {
			events.add("settleNano:" + actualNano);
			return new SettleResult(reservationId, actualNano, 0);
		}

		@Override
		public void markPendingVerify(String reservationId, String reason) {
			events.add("pending:" + reason);
		}

		@Override
		public void release(String reservationId, String reason, String operatorId) {
			events.add("release:" + reason);
		}

		@Override
		public SettleResult settlePending(String reservationId, long actualNano, String reason, String operatorId) {
			events.add("settlePending:" + actualNano);
			return new SettleResult(reservationId, actualNano, 0);
		}

		@Override
		public void releasePending(String reservationId, String reason, String operatorId) {
			events.add("releasePending:" + reason);
		}

		@Override
		public List<Reservation> pendingVerifications(String tenantId) {
			return List.of();
		}

		@Override
		public RechargeResult recharge(String tenantId, long amountNano, String voucherNo, String operatorId) {
			events.add("recharge:" + amountNano + ":" + voucherNo);
			if (seen.contains(voucherNo)) {
				return new RechargeResult("rcg-1", tenantId, amountNano, voucherNo, true);
			}
			seen.add(voucherNo);
			balance += amountNano;
			return new RechargeResult("rcg-1", tenantId, amountNano, voucherNo, false);
		}

		@Override
		public RechargeResult reverse(ReversalCommand command) {
			events.add("reverse:" + command.originalEntryId());
			return new RechargeResult("rcg-2", command.tenantId(), -1_000, command.operationId(), false);
		}

		@Override
		public Optional<BillingAccount> account(String tenantId) {
			return Optional.of(new BillingAccount(tenantId, "CNY", balance, 0, 0));
		}

		@Override
		public long reservedNano(String tenantId) {
			return 0;
		}

		@Override
		public void setOverdraft(String tenantId, long overdraftNano) {
		}

		@Override
		public void setProjectSublimit(String tenantId, String projectId, long limitNano) {
		}

		@Override
		public Optional<Long> projectSublimit(String projectId) {
			return Optional.empty();
		}

	}

}
