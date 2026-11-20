package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.billing.BillingAccount;
import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MeteredQuantity;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.billing.Unit;
import io.github.leohou0430.llmgw.billing.ReversalCommand;
import io.github.leohou0430.llmgw.billing.UsageEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0007 计费生命周期合同（任务3）：金额拒绝不发上游、前序安全预留撤回、 失败 attempt 不扣费、最终 attempt 仅结算一次、取消已知 usage 收费
 * / 未知 usage 挂账、价格更新不追溯在途、outcome 合法值表与 CAS 终态。
 */
class BillingLifecycleTest {

	/** 记录账本调用序列的 stub：断言引擎只推进预期的一次终态。 */
	private static final class RecordingLedger implements BillingLedger {

		final List<String> events = new ArrayList<>();

		Reservation next = new Reservation("res-1", "t1", "p1", "m", 1, 1_000);

		boolean deny;

		@Override
		public Reservation reserve(String tenantId, String projectId, String model, long priceVersion,
				long estimateNano) {
			if (deny) {
				return null;
			}
			events.add("reserve:" + estimateNano + ":v" + priceVersion);
			return next;
		}

		@Override
		public SettleResult settle(String reservationId, long actualInputTokens, long actualOutputTokens,
				Price frozen) {
			events.add("settle:" + actualInputTokens + ":" + actualOutputTokens + ":v" + frozen.version());
			return new SettleResult(reservationId, 1_000, 0);
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
			events.add("recharge:" + amountNano);
			return new RechargeResult("rcg-x", tenantId, amountNano, voucherNo, false);
		}

		@Override
		public RechargeResult reverse(ReversalCommand command) {
			events.add("reverse:" + command.originalEntryId());
			return new RechargeResult("rcg-y", command.tenantId(), -1_000, command.operationId(), false);
		}

		@Override
		public Optional<BillingAccount> account(String tenantId) {
			return Optional.empty();
		}

		@Override
		public long reservedNano(String tenantId) {
			return 0;
		}

		@Override
		public void setOverdraft(String tenantId, long overdraftNano) {
			events.add("overdraft:" + overdraftNano);
		}

		@Override
		public void setProjectSublimit(String tenantId, String projectId, long limitNano) {
		}

		@Override
		public Optional<Long> projectSublimit(String projectId) {
			return Optional.empty();
		}

	}

	private static final class StubPriceBook implements PriceBook {

		Price current = new Price(1, "m", 1_000_000, 2_000_000, 0);

		@Override
		public Price freeze(String model) {
			return current;
		}

		@Override
		public Optional<Price> priceFor(String model, long version) {
			return Optional.of(current);
		}

		@Override
		public List<Price> all() {
			return List.of(current);
		}

	}

	/** 固定 token 公式的 stub 计量器（engine-api 不依赖 sqlite 实现）。 */
	private static final class StubMeter implements BillingMeter {

		@Override
		public Unit unitFor(String kind) {
			return Unit.TOKENS;
		}

		@Override
		public MeteredQuantity estimate(Price frozen, BillingInput input) {
			if (frozen.inputNanoPerMillion() == 0 && frozen.outputNanoPerMillion() == 0) {
				throw new MissingPriceException(frozen.model(), frozen.version());
			}
			long raw = (input.estimatedInputTokens() * frozen.inputNanoPerMillion()
					+ input.estimatedOutputTokens() * frozen.outputNanoPerMillion() + 999_999) / 1_000_000;
			return new MeteredQuantity(Unit.TOKENS, input.estimatedInputTokens() + input.estimatedOutputTokens(),
					(raw + 999) / 1000 * 1000, MeteredQuantity.Quality.ESTIMATED);
		}

		@Override
		public MeteredQuantity extract(Price frozen, UsageEvidence evidence, long auxQuantity, Unit auxUnit) {
			if (!evidence.known()) {
				return MeteredQuantity.unknown(Unit.TOKENS);
			}
			long raw = (evidence.inputTokens() * frozen.inputNanoPerMillion()
					+ evidence.outputTokens() * frozen.outputNanoPerMillion() + 999_999) / 1_000_000;
			return new MeteredQuantity(Unit.TOKENS, evidence.inputTokens() + evidence.outputTokens(),
					(raw + 999) / 1000 * 1000, MeteredQuantity.Quality.AUTHORITATIVE);
		}

	}

	private static BillableCall open(BillingLedger ledger, PriceBook prices) {
		return BillableCall.open(ledger, prices, new StubMeter(), BillingInput.tokenCall("chat", 100, 50), "t1", "p1",
				"m");
	}

	@Test
	void openReturnsNullWhenTheAvailableAmountIsInsufficient() {
		var ledger = new RecordingLedger();
		ledger.deny = true;
		assertNull(open(ledger, new StubPriceBook()), "金额拒绝必须返回 null：调用方不得发上游");
		assertTrue(ledger.events.isEmpty(), "拒绝时不产生任何账本写");
	}

	@Test
	void releaseUnsubmittedReleasesTheReservation() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		assertNotNull(call);
		call.beginAttempt("route-a", "v1");
		call.releaseUnsubmitted("sending failed before upstream commit");
		assertTrue(ledger.events.contains("release:sending failed before upstream commit"));
		// 幂等：终态后再次撤回是 no-op。
		call.releaseUnsubmitted("second call");
		assertEquals(1, ledger.events.stream().filter(e -> e.startsWith("release")).count());
	}

	@Test
	void failedUnsubmittedAttemptChargesNothingAndTheFinalAttemptSettlesOnce() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		var first = call.beginAttempt("route-a", "v1"); // 发送前失败，从未提交：不推进终态
		var second = call.beginAttempt("route-b", "v1");
		second.markSubmitted("upstream-req-1");
		call.selectFinalAttempt(second.attemptId());
		second.complete(UsageEvidence.known(1_000, 500), BillableCall.OUTCOME_SETTLED);
		// 终态已推进：旧失败 attempt 再 complete 被 CAS 拒绝（不产生第二次扣费）。
		assertThrows(IllegalStateException.class,
				() -> first.complete(UsageEvidence.unknown(), BillableCall.OUTCOME_RELEASED));
		var settles = ledger.events.stream().filter(e -> e.startsWith("settle:")).toList();
		assertEquals(1, settles.size(), "最终 attempt 仅结算一次");
		assertEquals("settle:1000:500:v1", settles.get(0));
		assertFalse(ledger.events.stream().anyMatch(e -> e.startsWith("release")), "失败 attempt 不产生额外扣费/释放");
	}

	@Test
	void theSecondCompleteIsRejectedByTheCasContract() {
		var call = open(new RecordingLedger(), new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.complete(UsageEvidence.known(100, 10), BillableCall.OUTCOME_SETTLED);
		assertThrows(IllegalStateException.class,
				() -> attempt.complete(UsageEvidence.known(100, 10), BillableCall.OUTCOME_SETTLED), "终态推进只能发生一次");
	}

	@Test
	void cancelWithKnownUsageChargesTheKnownTokens() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.complete(UsageEvidence.known(700, 200), BillableCall.OUTCOME_SETTLED);
		assertTrue(ledger.events.contains("settle:700:200:v1"), "取消但已知 usage：按已知量收费");
	}

	@Test
	void unknownUsagePendsVerificationInsteadOfZeroCharging() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.complete(UsageEvidence.unknown(), BillableCall.OUTCOME_SETTLED);
		assertTrue(ledger.events.get(ledger.events.size() - 1).startsWith("pending:"), "未知 usage 不记零：挂账待核验");
	}

	@Test
	void submittedThenDisconnectedPendsAcrossRestart() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.markUnknown("upstream connection lost mid-stream");
		assertTrue(ledger.events.contains("pending:upstream connection lost mid-stream"));
	}

	@Test
	void inFlightCallKeepsItsFrozenPriceWhenPricesChange() {
		var ledger = new RecordingLedger();
		var prices = new StubPriceBook();
		var call = open(ledger, prices);
		prices.current = new Price(2, "m", 9_000_000, 9_000_000, 99); // 价格更新
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.complete(UsageEvidence.known(10, 10), BillableCall.OUTCOME_SETTLED);
		assertTrue(ledger.events.contains("settle:10:10:v1"), "价格更新后在途调用仍用冻结的 v1 结算");
	}

	@Test
	void unknownOutcomeValuesAreRejected() {
		var call = open(new RecordingLedger(), new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		assertThrows(IllegalArgumentException.class, () -> attempt.complete(UsageEvidence.known(1, 1), "SUCCEEDED"),
				"outcome 不由控制器任意拼写");
	}

	@Test
	void selectFinalAttemptIsCas() {
		var call = open(new RecordingLedger(), new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		// 未知 attemptId 不能被选为最终服务 attempt。
		assertThrows(IllegalArgumentException.class, () -> call.selectFinalAttempt("att-none"));
		call.selectFinalAttempt(attempt.attemptId());
		assertThrows(IllegalStateException.class, () -> call.selectFinalAttempt(attempt.attemptId()), "只能选定一次（CAS）");
	}

	@Test
	void anAttemptBelongsToExactlyOneCall() {
		var call = open(new RecordingLedger(), new StubPriceBook());
		var other = open(new RecordingLedger(), new StubPriceBook());
		var attempt = call.beginAttempt("route-a", "v1");
		// attempt 句柄只能推进所属 call 的终态：跨 call 引用其 attemptId 被拒绝。
		assertThrows(IllegalArgumentException.class, () -> other.selectFinalAttempt(attempt.attemptId()));
	}

	@Test
	void missingPriceRulePropagatesFromOpenBeforeAnyUpstream() {
		var ledger = new RecordingLedger();
		var prices = new StubPriceBook();
		prices.current = new Price(1, "m", 0, 0, 0); // token 单价全零 = 未定价
		assertThrows(MissingPriceException.class, () -> BillableCall.open(ledger, prices, new StubMeter(),
				BillingInput.tokenCall("chat", 100, 50), "t1", "p1", "m"), "缺计价规则：准入即拒绝（调用方不得发上游）");
		assertTrue(ledger.events.isEmpty(), "缺价拒绝不产生任何账本写");
	}

	@Test
	void nonTokenUnitsSettleByAmountViaCompleteNano() {
		var ledger = new RecordingLedger();
		var call = open(ledger, new StubPriceBook());
		var attempt = call.beginAttempt("images-route", "v1");
		attempt.markSubmitted(null);
		attempt.completeNano(20_000_000, BillableCall.OUTCOME_SETTLED);
		assertTrue(ledger.events.contains("settleNano:20000000"), "非 token 单位按金额结算（图片张数×单价已算好）");
	}

	@Test
	void billingDisabledCallIsANoOp() {
		var call = BillableCall.open(null, null, null, null, "t1", "p1", "m");
		assertNotNull(call, "billing pack 缺席时返回 no-op call（demo 不计费）");
		assertFalse(call.enabled());
		var attempt = call.beginAttempt("route-a", "v1");
		attempt.markSubmitted("upstream-req-1");
		attempt.complete(UsageEvidence.known(1, 1), BillableCall.OUTCOME_SETTLED);
		call.releaseUnsubmitted("late release");
		assertNull(call.billingCallId());
	}

}
