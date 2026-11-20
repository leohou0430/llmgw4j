package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MeteredQuantity;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.Unit;
import io.github.leohou0430.llmgw.billing.UsageEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 八类协议计价 fixtures（任务4）：token 类、图片、视频任务、音频时长、免费 管理面，以及缺价拒绝（未发上游）与未知证据挂账（不记零）。
 */
class BillingMeterTest {

	private final BillingMeter meter = new DefaultBillingMeter();

	private static final Price TOKEN_PRICE = new Price(1, "m", 1_000_000, 2_000_000, 0); // $1/M
																							// in,
																							// $2/M
																							// out（nano-CNY）

	@Test
	void chatAndEmbeddingsEstimateAndExtractByTokens() {
		var estimate = meter.estimate(TOKEN_PRICE, BillingInput.tokenCall("chat", 1_000, 500));
		assertEquals(Unit.TOKENS, estimate.unit());
		assertEquals(MeteredQuantity.Quality.ESTIMATED, estimate.quality());
		// 1000×1 + 500×2 = 2000 nano → 2000 nano 已对齐 micro。
		assertEquals(2_000, estimate.nano());

		var actual = meter.extract(TOKEN_PRICE, UsageEvidence.known(1_000_000, 500_000), 0, null);
		assertEquals(1_000_000 + 500_000, actual.quantity());
		// 1M×1 + 0.5M×2 = 2_000_000 nano。
		assertEquals(2_000_000, actual.nano());
		assertEquals(MeteredQuantity.Quality.AUTHORITATIVE, actual.quality());
	}

	@Test
	void imagesPriceByCountAndRejectMissingImagePrice() {
		var priced = new Price(1, "img", 0, 0, 0, 5_000_000, 0, 0); // 0.005 CNY/张
		var quantity = meter.estimate(priced, new BillingInput("images", 0, 0, 4, 0));
		assertEquals(Unit.IMAGES, quantity.unit());
		assertEquals(4, quantity.quantity());
		assertEquals(20_000_000, quantity.nano());

		var unpriced = new Price(1, "img", 1_000_000, 2_000_000, 0); // 只有 token 价
		assertThrows(MissingPriceException.class,
				() -> meter.estimate(unpriced, new BillingInput("images", 0, 0, 4, 0)), "图片未定价：准入即拒绝（未发上游）");
	}

	@Test
	void videoTasksPriceOncePerTaskCreation() {
		var priced = new Price(1, "vid", 0, 0, 0, 0, 500_000_000, 0); // 0.5 CNY/任务
		var quantity = meter.estimate(priced, new BillingInput("videos", 0, 0, 1, 0));
		assertEquals(Unit.TASKS, quantity.unit());
		assertEquals(1, quantity.quantity());
		assertEquals(500_000_000, quantity.nano());
	}

	@Test
	void audioKindsEstimateSecondsFromRequestBytes() {
		var priced = new Price(1, "tts", 0, 0, 0, 0, 0, 1_000_000); // 0.001 CNY/秒
		// 320KB @32KB/s = 10 秒。
		var speech = meter.estimate(priced, new BillingInput("speech", 0, 0, 1, 320_000));
		assertEquals(Unit.SECONDS, speech.unit());
		assertEquals(10, speech.quantity());
		assertEquals(10_000_000, speech.nano());

		var transcribed = meter.extract(priced, UsageEvidence.unknown(), 0, Unit.SECONDS);
		assertEquals(MeteredQuantity.Quality.UNKNOWN, transcribed.quality(), "转写无权威时长证据：挂账不记零");
		assertEquals(0, transcribed.nano());
	}

	@Test
	void fileAndBatchManagementIsExplicitlyFree() {
		var quantity = meter.estimate(TOKEN_PRICE, new BillingInput("files", 0, 0, 1, 100));
		assertEquals(Unit.FREE, quantity.unit());
		assertEquals(0, quantity.nano());

		var batches = meter.estimate(TOKEN_PRICE, new BillingInput("batches", 0, 0, 1, 100));
		assertEquals(Unit.FREE, batches.unit());
		assertEquals(0, batches.nano());
	}

	@Test
	void rerankAndResponsesShareTheTokenFormula() {
		var rerank = meter.estimate(TOKEN_PRICE, BillingInput.tokenCall("rerank", 2_000, 100));
		assertEquals(Unit.TOKENS, rerank.unit());
		var responses = meter.extract(TOKEN_PRICE, UsageEvidence.known(500_000, 0), 0, Unit.TOKENS);
		assertEquals(500_000, responses.nano());
	}

	@Test
	void unpricedTokenModelIsRejectedBeforeUpstream() {
		var unpriced = new Price(1, "new-model", 0, 0, 0);
		assertThrows(MissingPriceException.class,
				() -> meter.estimate(unpriced, BillingInput.tokenCall("chat", 100, 50)), "缺计价规则拒绝（未发上游）");
	}

	@Test
	void unknownUsageEvidencePendsInsteadOfZeroCharging() {
		var unknown = meter.extract(TOKEN_PRICE, UsageEvidence.unknown(), 0, null);
		assertEquals(MeteredQuantity.Quality.UNKNOWN, unknown.quality());
		assertEquals(0, unknown.nano());
		assertTrue(unknown.quantity() == 0);
	}

	@Test
	void fractionalNanoRoundsUpToWholeMicro() {
		// 333 tokens × 1_000_000/1M = 333 nano → 取整 1000 nano。
		var actual = meter.extract(TOKEN_PRICE, UsageEvidence.known(333, 0), 0, null);
		assertEquals(1_000, actual.nano());
	}

}
