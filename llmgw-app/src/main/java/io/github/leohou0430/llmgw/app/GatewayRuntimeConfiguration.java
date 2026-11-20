package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.model.ModelCatalog;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.governance.inmemory.DynamicGovernanceSettings;
import io.github.leohou0430.llmgw.governance.inmemory.GovernanceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Exposes an already composed native graph to the HTTP application, without
 * implementation scanning.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRuntimeConfiguration {

	@Bean(destroyMethod = "close")
	public ExtensionContext gatewayRuntime(GatewayProperties properties, ObjectProvider<MeterRegistry> registries,
			Environment environment) {
		legacyRedisProperties(properties, environment);
		var context = new ExtensionContext();
		try {
			context.external(GatewayProperties.class, properties);
			MeterRegistry registry = registries.getIfAvailable();
			if (registry != null)
				context.external(MeterRegistry.class, registry);
			context.loadModule("in-memory");
			if (properties.getRedis().isEnabled())
				context.loadModule("redis");
			context.loadModule("jdk-http");
			context.loadModule("conversion");
			context.loadModule("control-sqlite");
			if (properties.getBilling().isEnabled())
				context.loadModule("billing-sqlite");
			if (properties.getRecording().isEnabled())
				context.loadModule("recording-local");
			// Chapter-9 §9.4.5/§9.5.4：客户幂等键与审批工作流（持久 SQLite）。
			context.loadModule("idempotency-sqlite");
			context.loadModule("approval-sqlite");
			// Chapter-9 §9.3（ADR-0010）MCP 网关：默认关闭，显式开启。
			if (properties.getMcp().isEnabled()) {
				context.external(io.github.leohou0430.llmgw.mcp.McpSettings.class, mcpSettings(properties));
				context.loadModule("mcp-default");
			}
			for (String module : properties.getExtensionModules())
				context.loadModule(module);
			context.seal();
			context.get(io.github.leohou0430.llmgw.control.local.RouteVersionService.class);
			context.get(ApiKeyRegistry.class);
			if (properties.getGovernance().isSnapshotAutoRestore())
				context.get(SnapshotStore.class).load();
			// Chapter-9 §9.6.3：gen_ai.* span 属性桥（-Potel profile 下有 registry）。
			MeterRegistry registryBean = registries.getIfAvailable();
			if (registryBean instanceof io.micrometer.observation.ObservationRegistry observationRegistry) {
				var chainInstance = context.get(GovernanceChain.class);
				if (chainInstance instanceof io.github.leohou0430.llmgw.governance.inmemory.DefaultGovernanceChain chain) {
					chain.setGenAiSink(new GenAiSpanBridge(observationRegistry));
				}
			}
			return context;
		}
		catch (RuntimeException | Error failure) {
			try {
				context.close();
			}
			catch (RuntimeException close) {
				failure.addSuppressed(close);
			}
			throw failure;
		}
	}

	/**
	 * Compatibility only at the Boot edge; native modules know gateway.redis.*
	 * exclusively.
	 */
	private static void legacyRedisProperties(GatewayProperties p, Environment env) {
		var r = p.getRedis();
		if (r.isEnabled() && env.containsProperty("spring.data.redis.url")) {
			throw new IllegalArgumentException(
					"migrate spring.data.redis.url to gateway.redis host/port/username/password/database/ssl");
		}
		if (!env.containsProperty("gateway.redis.host"))
			r.setHost(env.getProperty("spring.data.redis.host", r.getHost()));
		if (!env.containsProperty("gateway.redis.port"))
			r.setPort(env.getProperty("spring.data.redis.port", Integer.class, r.getPort()));
		if (!env.containsProperty("gateway.redis.database"))
			r.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, r.getDatabase()));
		if (!env.containsProperty("gateway.redis.username"))
			r.setUsername(env.getProperty("spring.data.redis.username", r.getUsername()));
		if (!env.containsProperty("gateway.redis.password"))
			r.setPassword(env.getProperty("spring.data.redis.password", r.getPassword()));
		if (!env.containsProperty("gateway.redis.ssl"))
			r.setSsl(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, r.isSsl()));
		if (!env.containsProperty("gateway.redis.timeout-ms") && env.containsProperty("spring.data.redis.timeout")) {
			r.setTimeoutMs(Math.toIntExact(org.springframework.boot.convert.DurationStyle
				.detectAndParse(env.getProperty("spring.data.redis.timeout"))
				.toMillis()));
		}
	}

	@Bean(destroyMethod = "")
	public io.github.leohou0430.llmgw.provider.UpstreamProvider upstreamProvider(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.provider.UpstreamProvider.class);
	}

	@Bean(name = { "apiKeyRegistry", "keyAdminService" }, destroyMethod = "")
	public SqliteApiKeyRegistry apiKeyRegistry(ExtensionContext c) {
		return (SqliteApiKeyRegistry) c.get(ApiKeyRegistry.class);
	}

	@Bean(destroyMethod = "")
	public TokenLimiter tokenLimiter(ExtensionContext c) {
		return c.get(TokenLimiter.class);
	}

	@Bean(destroyMethod = "")
	public BudgetLedger budgetLedger(ExtensionContext c) {
		return c.get(BudgetLedger.class);
	}

	@Bean(destroyMethod = "")
	public RequestLogSink requestLogSink(ExtensionContext c) {
		return c.get(RequestLogSink.class);
	}

	@Bean(destroyMethod = "")
	public Guardrail guardrail(ExtensionContext c) {
		return c.get(Guardrail.class);
	}

	@Bean(destroyMethod = "")
	public Embedder embedder(ExtensionContext c) {
		return c.get(Embedder.class);
	}

	@Bean(destroyMethod = "")
	public TokenValidator tokenValidator(ExtensionContext c) {
		return c.get(TokenValidator.class);
	}

	@Bean(destroyMethod = "")
	public TaskRouteSticky taskRouteSticky(ExtensionContext c) {
		return c.get(TaskRouteSticky.class);
	}

	@Bean(destroyMethod = "")
	public SemanticCache semanticCache(ExtensionContext c) {
		return c.get(SemanticCache.class);
	}

	@Bean(destroyMethod = "")
	public AuditSink auditSink(ExtensionContext c) {
		return c.get(AuditSink.class);
	}

	@Bean(destroyMethod = "")
	public GovernanceMetrics governanceMetrics(ExtensionContext c) {
		return c.get(GovernanceMetrics.class);
	}

	@Bean(destroyMethod = "")
	public SnapshotStore snapshotStore(ExtensionContext c) {
		return c.get(SnapshotStore.class);
	}

	@Bean(destroyMethod = "")
	public RouteConfigSource routeConfigSource(ExtensionContext c) {
		return c.get(RouteConfigSource.class);
	}

	@Bean(destroyMethod = "")
	public Router router(ExtensionContext c) {
		return c.get(Router.class);
	}

	@Bean(destroyMethod = "")
	public CircuitBreaker circuitBreaker(ExtensionContext c) {
		return c.get(CircuitBreaker.class);
	}

	@Bean(destroyMethod = "")
	public HeaderPolicy headerPolicy(ExtensionContext c) {
		return c.get(HeaderPolicy.class);
	}

	@Bean(destroyMethod = "")
	public ModelCatalog modelCatalog(ExtensionContext c) {
		return c.get(ModelCatalog.class);
	}

	@Bean(destroyMethod = "")
	public GovernanceChain governanceChain(ExtensionContext c) {
		return c.get(GovernanceChain.class);
	}

	@Bean(destroyMethod = "")
	public ExecutorService executorService(ExtensionContext c) {
		return c.get(ExecutorService.class);
	}

	@Bean(destroyMethod = "")
	public DynamicGovernanceSettings dynamicGovernanceSettings(ExtensionContext c) {
		return c.get(DynamicGovernanceSettings.class);
	}

	@Bean(destroyMethod = "")
	public ConversionService conversionService(ExtensionContext c) {
		return c.get(ConversionService.class);
	}

	/** ADR-0007 billing pack beans; only bound when gateway.billing.enabled=true. */
	@Bean(destroyMethod = "")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gateway.billing.enabled",
			havingValue = "true")
	public io.github.leohou0430.llmgw.billing.BillingLedger billingLedger(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.billing.BillingLedger.class);
	}

	@Bean(destroyMethod = "")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gateway.billing.enabled",
			havingValue = "true")
	public io.github.leohou0430.llmgw.billing.PriceBook priceBook(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.billing.PriceBook.class);
	}

	@Bean(destroyMethod = "")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gateway.billing.enabled",
			havingValue = "true")
	public io.github.leohou0430.llmgw.billing.BillingMeter billingMeter(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.billing.BillingMeter.class);
	}

	/**
	 * Task-5 bounded body recording beans; only bound when
	 * gateway.recording.enabled=true.
	 */
	@Bean(destroyMethod = "")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gateway.recording.enabled",
			havingValue = "true")
	public io.github.leohou0430.llmgw.recording.RecordingStore recordingStore(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.recording.RecordingStore.class);
	}

	/** Chapter-9 §9.4.5 客户幂等键存储（持久 SQLite，随控制面数据库装配）。 */
	@Bean(destroyMethod = "")
	public io.github.leohou0430.llmgw.idempotency.IdempotencyStore idempotencyStore(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.idempotency.IdempotencyStore.class);
	}

	/** Chapter-9 §9.5.4 审批工作流（持久 CAS 状态机）。 */
	@Bean(destroyMethod = "")
	public io.github.leohou0430.llmgw.approval.ApprovalService approvalService(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.approval.ApprovalService.class);
	}

	/** Chapter-9 §9.3（ADR-0010）MCP 网关；仅 gateway.mcp.enabled=true 时装配。 */
	@Bean(destroyMethod = "")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gateway.mcp.enabled",
			havingValue = "true")
	public io.github.leohou0430.llmgw.mcp.McpGatewayService mcpGatewayService(ExtensionContext c) {
		return c.get(io.github.leohou0430.llmgw.mcp.McpGatewayService.class);
	}

	/** GatewayProperties.Mcp → 领域设置（出口白名单与默认拒绝规则）。 */
	static io.github.leohou0430.llmgw.mcp.McpSettings mcpSettings(GatewayProperties properties) {
		GatewayProperties.Mcp mcp = properties.getMcp();
		List<io.github.leohou0430.llmgw.mcp.McpServerDef> servers = new java.util.ArrayList<>();
		for (GatewayProperties.Mcp.McpServerDef def : mcp.getServers()) {
			servers.add(new io.github.leohou0430.llmgw.mcp.McpServerDef(def.getName(), def.getUrl(), def.isLegacy(),
					def.getCredential()));
		}
		List<io.github.leohou0430.llmgw.mcp.McpRule> rules = new java.util.ArrayList<>();
		for (GatewayProperties.Mcp.McpRuleDef rule : mcp.getRules()) {
			rules.add(new io.github.leohou0430.llmgw.mcp.McpRule(rule.getSubject(), rule.getServer(), rule.getTool(),
					rule.getAction()));
		}
		return new io.github.leohou0430.llmgw.mcp.McpSettings(servers, rules, mcp.getMaxBodyBytes(),
				mcp.getAllowedOrigins(), mcp.getUpstreamTimeoutMs());
	}

}
