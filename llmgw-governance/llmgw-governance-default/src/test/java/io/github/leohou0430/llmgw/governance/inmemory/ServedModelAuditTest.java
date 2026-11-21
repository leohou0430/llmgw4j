package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Chapter-9 §9.2.2 对账契约：finish 审计记录「实际服务的上游模型」 （servedModel，targetModel/fallback
 * 重写后），与「客户请求别名」（model） 分别可读——客户端看到同一模型名，不代表平台未经记录地替换了实际能力。 缓存命中/未发上游（servedModel
 * 留空）不产出该字段。
 */
class ServedModelAuditTest {

	private static DefaultGovernanceChain chain(AuditSink sink, java.util.concurrent.ExecutorService sideCar) {
		var p = new GatewayProperties();
		return new DefaultGovernanceChain(new ManagedApiKeyRegistry(p, new JsonKeyStore(p)), new AtomicTokenLimiter(p),
				new InMemoryUsageLedger(), new CaffeineSemanticCache(p), sink, sideCar, new GovernanceMetrics(null), p);
	}

	private static Map<String, Object> latestDetail(AuditSink sink) {
		List<AuditSink.AuditEvent> recent = sink.recent(1);
		assertEquals(1, recent.size(), "one finish audit event expected");
		return recent.getFirst().detail();
	}

	@Test
	void servedModelIsRecordedWhenAnUpstreamModelServedTheOutput() throws Exception {
		InMemoryAuditSink sink = new InMemoryAuditSink();
		try (var sideCar = Executors.newSingleThreadExecutor()) {
			DefaultGovernanceChain chain = chain(sink, sideCar);
			GatewayContext context = new GatewayContext("demo", "claude-alias", "r", false, 1);
			// 引擎在成功提交上游后设置：本路由 targetModel 重写后的实际模型。
			context.setServedModel("deepseek-chat");
			chain.finish(context, true);
			sideCar.submit(() -> {
			}).get();
			Map<String, Object> details = latestDetail(sink);
			assertEquals("claude-alias", details.get("model"), "client alias stays recorded as-is");
			assertEquals("deepseek-chat", details.get("servedModel"), "the served model is recorded separately");
		}
	}

	@Test
	void cacheHitsAndUnsentRequestsProduceNoServedModelField() throws Exception {
		InMemoryAuditSink sink = new InMemoryAuditSink();
		try (var sideCar = Executors.newSingleThreadExecutor()) {
			DefaultGovernanceChain chain = chain(sink, sideCar);
			GatewayContext context = new GatewayContext("demo", "m", "r", false, 1);
			// 缓存命中：无上游模型服务本次输出（markCached 路径不设 servedModel）。
			context.markCached();
			chain.finish(context, true);
			sideCar.submit(() -> {
			}).get();
			assertNull(latestDetail(sink).get("servedModel"), "no upstream served a cache hit");
		}
	}

}
