package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.BudgetLedger;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.Guardrail;
import io.github.leohou0430.llmgw.core.governance.RequestLogSink;
import io.github.leohou0430.llmgw.core.governance.TokenValidator;
import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The ONE governance implementation shared by both engines. Hot path: authentication,
 * quota and token rate limiting (pure in-memory atomics). Side car: ledger writes, audit
 * events, metrics, cache writes (virtual threads).
 */
public class DefaultGovernanceChain implements GovernanceChain {

	private final ApiKeyRegistry apiKeys;

	private final TokenLimiter limiter;

	private final UsageLedger ledger;

	private final SemanticCache cache;

	private final AuditSink audit;

	private final ExecutorService sideCar;

	private final GovernanceMetrics metrics;

	private final GatewayProperties properties;

	/** v13 $ hard budgets; nullable so custom packs can omit the bean. */
	private final BudgetLedger budgets;

	/** v13 model prices feeding $ estimates; an absent catalog prices at $0. */
	private final io.github.leohou0430.llmgw.core.model.ModelCatalog catalog;

	/** v13 request log; nullable (custom packs may ship their own via SPI). */
	private final RequestLogSink requestLog;

	/** W4.1 content guardrails; nullable = no inspection. */
	private final Guardrail guardrail;

	/** W4.2 semantic cache keying; nullable = exact-hash keys only. */
	private final io.github.leohou0430.llmgw.core.governance.Embedder embedder;

	/** B1 JWT ingress validation; nullable = registry keys only. */
	private final io.github.leohou0430.llmgw.core.governance.TokenValidator tokenValidator;

	private final io.github.leohou0430.llmgw.tokenizer.TokenCounter tokenCounter;

	/** B4: group -> hard budget (micro-USD); empty = no group metering. */
	private final Map<String, Long> groupBudgets;

	/** B4: per-model token-bucket overrides, matched exact > prefix > *. */
	private final List<GatewayProperties.ModelLimit> modelLimits;

	/** B2: in-process vector store for cosine similarity lookup (semantic cache). */
	private final io.github.leohou0430.llmgw.governance.inmemory.InMemoryVectorStore vectorStore;

	/**
	 * Engine label is resolved once on first use, not in the constructor.
	 * {@code EngineKindEnvironmentPostProcessor} publishes the active engine during
	 * environment preparation, so the value is already present by the time this bean is
	 * built; caching only a <em>resolved</em> label means a custom composition root that
	 * registers the engine later can never pin {@code unknown} for the process lifetime.
	 * The volatile read is far cheaper than the synchronized Properties lookup it
	 * replaces.
	 */
	private volatile String engineLabel;

	/**
	 * Chapter-9 §9.5.3: the governance policy version stamped into every admission
	 * decision and audit event. Monotonic; bumped alongside
	 * {@link DynamicGovernanceSettings} updates so each judgment records WHICH rule set
	 * decided it (no unexplainable black-box matrix).
	 */
	private volatile long policyVersion = 1;

	/** Chapter-9 §9.6.3 gen_ai.* 观测出口；nullable（无桥接 = 只留指标）。 */
	private volatile io.github.leohou0430.llmgw.core.governance.GenAiSpanSink genAiSink;

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, null, null, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, budgets, catalog, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog,
			RequestLogSink requestLog) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, budgets, catalog, requestLog, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog, RequestLogSink requestLog,
			Guardrail guardrail) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, budgets, catalog, requestLog,
				guardrail, null, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog, RequestLogSink requestLog,
			Guardrail guardrail, io.github.leohou0430.llmgw.core.governance.Embedder embedder) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, budgets, catalog, requestLog,
				guardrail, embedder, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog, RequestLogSink requestLog,
			Guardrail guardrail, io.github.leohou0430.llmgw.core.governance.Embedder embedder,
			io.github.leohou0430.llmgw.core.governance.TokenValidator tokenValidator) {
		this(apiKeys, limiter, ledger, cache, audit, sideCar, metrics, properties, budgets, catalog, requestLog,
				guardrail, embedder, tokenValidator, null);
	}

	public DefaultGovernanceChain(ApiKeyRegistry apiKeys, TokenLimiter limiter, UsageLedger ledger, SemanticCache cache,
			AuditSink audit, ExecutorService sideCar, GovernanceMetrics metrics, GatewayProperties properties,
			BudgetLedger budgets, io.github.leohou0430.llmgw.core.model.ModelCatalog catalog, RequestLogSink requestLog,
			Guardrail guardrail, io.github.leohou0430.llmgw.core.governance.Embedder embedder,
			io.github.leohou0430.llmgw.core.governance.TokenValidator tokenValidator,
			io.github.leohou0430.llmgw.tokenizer.TokenCounter tokenCounter) {
		this.apiKeys = apiKeys;
		this.limiter = limiter;
		this.ledger = ledger;
		this.cache = cache;
		this.audit = audit;
		this.sideCar = sideCar;
		this.metrics = metrics;
		this.properties = properties;
		this.budgets = budgets;
		this.catalog = catalog;
		this.requestLog = requestLog;
		this.guardrail = guardrail;
		this.embedder = embedder;
		this.tokenValidator = tokenValidator;
		this.tokenCounter = tokenCounter;
		this.groupBudgets = groupBudgetsFrom(properties);
		this.modelLimits = List.copyOf(properties.getModelLimits());
		this.vectorStore = new io.github.leohou0430.llmgw.governance.inmemory.InMemoryVectorStore(
				properties.getCache().getSemanticVectorCapacity());
	}

	private static Map<String, Long> groupBudgetsFrom(GatewayProperties properties) {
		Map<String, Long> out = new LinkedHashMap<>();
		properties.getGroupBudgets().forEach(gb -> {
			if (gb.getGroup() != null && !gb.getGroup().isBlank() && gb.getBudgetMicroUsd() > 0) {
				out.put(gb.getGroup(), gb.getBudgetMicroUsd());
			}
		});
		return Map.copyOf(out);
	}

	/** B4: the first matching per-model limit (exact > prefix > *), or null. */
	private GatewayProperties.ModelLimit modelLimitFor(String model) {
		GatewayProperties.ModelLimit wildcard = null;
		GatewayProperties.ModelLimit prefix = null;
		for (GatewayProperties.ModelLimit limit : modelLimits) {
			String pattern = limit.getModel();
			if (pattern.equals(model)) {
				return limit;
			}
			if (prefix == null && pattern.endsWith("*") && model != null
					&& model.startsWith(pattern.substring(0, pattern.length() - 1))) {
				prefix = limit;
			}
			if (wildcard == null && "*".equals(pattern)) {
				wildcard = limit;
			}
		}
		return prefix != null ? prefix : wildcard;
	}

	/** See {@link #engineLabel}: only a resolved value is ever cached. */
	private String engineLabel() {
		String label = engineLabel;
		if (label == null) {
			// Contract with EngineKind.SYSTEM_PROPERTY. This module deliberately
			// does not depend on the engine modules (that would invert the
			// dependency direction); the string is the published SPI contract.
			label = System.getProperty("llmgw.engine", "unknown");
			if (!"unknown".equals(label)) {
				engineLabel = label;
			}
		}
		return label;
	}

	/** Chapter-9 §9.5.3 policy version (monotonic; stamped on every judgment). */
	public long policyVersion() {
		return policyVersion;
	}

	/** Explicit set (composition roots derive it from durable config versions). */
	public void setPolicyVersion(long version) {
		if (version >= 1) {
			this.policyVersion = version;
		}
	}

	/** Monotonic bump on every governance hot update. */
	public long bumpPolicyVersion() {
		return ++policyVersion;
	}

	/** Chapter-9 §9.6.3 gen_ai.* 桥接（组合根装配；缺席 = 只留指标）。 */
	public void setGenAiSink(io.github.leohou0430.llmgw.core.governance.GenAiSpanSink sink) {
		this.genAiSink = sink;
	}

	@Override
	public GatewayDecision enter(String authorizationHeader, ParsedRequest request, Route route) {
		if (!properties.isGovernanceEnabled()) {
			return GatewayDecision.allow("anonymous", request, route);
		}
		String token = bearer(authorizationHeader);
		ApiKeyRegistry.ApiKey key = apiKeys.find(token).orElse(null);
		if (key == null && tokenValidator != null) {
			// B1: registry miss falls through to the ingress-auth validator
			// (JWT/OIDC) -- the virtual key carries its own allowance.
			key = tokenValidator.validate(authorizationHeader).orElse(null);
		}
		if (key == null) {
			metrics.count("llmgw.denied", "reason", "invalid_key");
			logDeny(request, "", "invalid api key", 401);
			return GatewayDecision.deny(401, "invalid api key");
		}
		// Chapter-9 §9.5.2 主体映射：认证主体必须映射到稳定的财务归属。缺少
		// tenant/project 元数据的 key 无法归属——生产模式或持久金额账本启用时
		// 拒绝；演示级 budgetMicroUsd 实验预算（生产本就被禁用）保留 keyId
		// 回退（声明过的 legacy 行为），不拦截演示合同。
		String keyTenant = key.metadata() == null ? "" : key.metadata().getOrDefault("llmgw.tenantId", "");
		String keyProject = key.metadata() == null ? "" : key.metadata().getOrDefault("llmgw.projectId", "");
		if ((keyTenant.isBlank() || keyProject.isBlank())
				&& (properties.isProduction() || properties.getBilling().isEnabled())) {
			metrics.count("llmgw.denied", "reason", "unattributed_subject");
			logDeny(request, key.id(), "key lacks llmgw.tenantId/llmgw.projectId attribution", 401);
			return GatewayDecision.deny(401, "unattributed subject: key metadata must carry tenant and project");
		}
		boolean monetaryBudget = key.budgetMicroUsd() > 0 || groupBudgets.getOrDefault(key.group(), 0L) > 0;
		if (monetaryBudget && (properties.isProduction() || budgets == null)) {
			return GatewayDecision.deny(503,
					"production monetary budgets are unavailable without durable accounting and cost bounds");
		}
		if (monetaryBudget && (catalog == null || catalog.find(request.model()).isEmpty())) {
			return GatewayDecision.deny(503, "model price is unknown for budgeted request");
		}
		// Task polling and content downloads carry no model selection; the
		// allow-list applies to what the caller asked the model to do.
		if (!key.allowedModels().isEmpty() && !request.model().isBlank()
				&& !key.allowedModels().contains(request.model())) {
			metrics.count("llmgw.denied", "reason", "model_not_allowed");
			logDeny(request, key.id(), "model not allowed for this key", 403);
			return GatewayDecision.deny(403, "model not allowed for this key");
		}
		// W4.1: content guardrails run after auth (so abuse is attributable)
		// and before any metering -- blocked content never spends quota.
		if (guardrail != null) {
			Guardrail.Verdict verdict = guardrail.inspectRequest(request);
			if (verdict.action() != Guardrail.Action.ALLOW) {
				int status = verdict.action() == Guardrail.Action.UNAVAILABLE ? 503 : 400;
				metrics.count("llmgw.denied", "reason", "guardrail");
				metrics.count("llmgw.guardrail", "guardrail", guardrail.name());
				logDeny(request, key.id(), verdict.reason(), status);
				return GatewayDecision.deny(status, verdict.reason());
			}
		}
		if (tokenCounter != null) {
			int estimate = tokenCounter.estimate(request);
			if (estimate != request.estimatedTokens())
				request = new ParsedRequest(request.rawJson(), request.kind(), request.model(), request.stream(),
						request.promptText(), estimate, request.units(), request.cacheFingerprint());
		}
		int reserved = Math.max(1, request.estimatedTokens());
		// v13 $ hard budget, checked BEFORE the token reservation so a denial
		// needs no cross-ledger refund. The estimate assumes a completion the
		// size of the prompt: output tokens usually cost more per token, so
		// the bias is deliberately toward over-reserving.
		long budgetReserved = 0;
		if (budgets != null && key.budgetMicroUsd() > 0) {
			var price = catalog == null ? null : catalog.find(request.model()).orElse(null);
			budgetReserved = io.github.leohou0430.llmgw.governance.QuotaMath.costMicroUsd(reserved, reserved, price);
			if (!budgets.tryReserve(key.id(), budgetReserved, key.budgetMicroUsd())) {
				metrics.count("llmgw.denied", "reason", "budget_exceeded");
				logDeny(request, key.id(), "spend budget exceeded", 429);
				return GatewayDecision.deny(429, "spend budget exceeded");
			}
		}
		// B4: group-level hard budget (aggregated across the group's keys).
		long groupReserved = 0;
		if (budgets != null && key.group() != null && !key.group().isBlank()) {
			Long groupBudget = groupBudgets.get(key.group());
			if (groupBudget != null) {
				var price = catalog == null ? null : catalog.find(request.model()).orElse(null);
				groupReserved = io.github.leohou0430.llmgw.governance.QuotaMath.costMicroUsd(reserved, reserved, price);
				if (!budgets.tryReserve("group:" + key.group(), groupReserved, groupBudget)) {
					if (budgetReserved > 0) {
						budgets.settle(key.id(), budgetReserved, 0);
					}
					metrics.count("llmgw.denied", "reason", "group_budget_exceeded");
					logDeny(request, key.id(), "group spend budget exceeded", 429);
					return GatewayDecision.deny(429, "group spend budget exceeded");
				}
			}
		}
		if (!limiter.tryAcquire(key.id(), reserved)) {
			if (budgetReserved > 0) {
				budgets.settle(key.id(), budgetReserved, 0);
			}
			if (groupReserved > 0) {
				budgets.settle("group:" + key.group(), groupReserved, 0);
			}
			metrics.count("llmgw.denied", "reason", "rate_limited");
			logDeny(request, key.id(), "token rate limit exceeded", 429);
			return GatewayDecision.deny(429, "token rate limit exceeded");
		}
		// B4: per-model token bucket on top of the per-key bucket.
		GatewayProperties.ModelLimit modelLimit = modelLimitFor(request.model());
		if (modelLimit != null && modelLimit.getTokensPerSecond() > 0) {
			if (!limiter.tryAcquire("model:" + request.model(), reserved, modelLimit.getTokensPerSecond(),
					modelLimit.getBurst() > 0 ? modelLimit.getBurst() : modelLimit.getTokensPerSecond())) {
				limiter.refund(key.id(), reserved);
				if (budgetReserved > 0) {
					budgets.settle(key.id(), budgetReserved, 0);
				}
				if (groupReserved > 0) {
					budgets.settle("group:" + key.group(), groupReserved, 0);
				}
				metrics.count("llmgw.denied", "reason", "model_rate_limited");
				logDeny(request, key.id(), "model rate limit exceeded", 429);
				return GatewayDecision.deny(429, "model rate limit exceeded");
			}
		}
		// Atomic reservation includes in-flight requests. A plain
		// used()+estimate>quota check lets N concurrent callers all pass.
		String reservationId = java.util.UUID.randomUUID().toString();
		if (!ledger.tryReserve(new UsageLedger.Reservation(reservationId, key.id(), keyTenant, keyProject, reserved,
				key.quotaTokens()))) {
			limiter.refund(key.id(), reserved);
			if (modelLimit != null && modelLimit.getTokensPerSecond() > 0) {
				limiter.refund("model:" + request.model(), reserved);
			}
			if (budgetReserved > 0) {
				budgets.settle(key.id(), budgetReserved, 0);
			}
			if (groupReserved > 0) {
				budgets.settle("group:" + key.group(), groupReserved, 0);
			}
			metrics.count("llmgw.denied", "reason", "quota_exceeded");
			logDeny(request, key.id(), "token quota exceeded", 429);
			return GatewayDecision.deny(429, "token quota exceeded");
		}
		metrics.count("llmgw.requests", "engine", engineLabel(), "route", route == null ? "none" : route.id(),
				"protocol", request.kind().wireName());
		GatewayDecision decision = GatewayDecision
			.allow(key.id(),
					request.withCacheNamespace(
							route == null ? "" : ProtocolCodec.fingerprint(ProtocolCodec.toJson(route))),
					route)
			.withReservation(reservationId)
			.withScope(keyTenant.isBlank() ? key.id() : keyTenant, keyProject.isBlank() ? key.id() : keyProject)
			.withPolicyVersion(policyVersion);
		if (budgetReserved > 0) {
			decision = decision.withBudgetReserved(budgetReserved);
		}
		if (groupReserved > 0) {
			decision = decision.withGroup(key.group(), groupReserved);
		}
		return decision;
	}

	@Override
	public Optional<String> lookupCache(String keyId, ParsedRequest request) {
		if (!properties.isGovernanceEnabled() || request.stream()) {
			return Optional.empty();
		}
		Optional<String> hit = cache.get(cacheKey(keyId, request));
		if (hit.isEmpty() && embedder != null && !request.stream()) {
			// B2: cosine similarity lookup — embed once, scan the vector
			// store for a close match, then read that entry's cached response.
			double[] vector = embedder.embed(request.promptText());
			if (vector.length > 0) {
				double threshold = properties.getCache().getSemanticThreshold();
				if (threshold > 0) {
					String similar = vectorStore.findSimilar(semanticPartition(keyId, request), vector, threshold);
					if (similar != null) {
						hit = cache.get(similar);
						if (hit.isPresent()) {
							metrics.count("llmgw.cache", "result", "similar_hit");
						}
					}
				}
			}
		}
		metrics.count("llmgw.cache", "result", hit.isPresent() ? "hit" : "miss");
		return hit;
	}

	@Override
	public void cache(String keyId, ParsedRequest request, String response) {
		if (!properties.isGovernanceEnabled() || request.stream() || !ProtocolCodec.isCacheableResponse(response)) {
			return;
		}
		double[] vector = new double[0];
		if (embedder != null && properties.getCache().getSemanticThreshold() > 0) {
			vector = embedder.embed(request.promptText());
		}
		String key = cacheKey(keyId, request);
		if (vector.length > 0) {
			vectorStore.put(semanticPartition(keyId, request), key, vector);
		}
		sideCar.submit(() -> cache.put(key, response));
	}

	/**
	 * Single-parse fast path: the controller already built the JsonNode tree and derived
	 * this verdict from it. Without this override the interface default would forward to
	 * the 3-arg method, which re-parses the body -- silently undoing the optimization
	 * this overload exists for.
	 */
	@Override
	public void cache(String keyId, ParsedRequest request, String response, boolean cacheable) {
		if (!properties.isGovernanceEnabled() || request.stream() || !cacheable) {
			return;
		}
		double[] vector = new double[0];
		if (embedder != null && properties.getCache().getSemanticThreshold() > 0) {
			vector = embedder.embed(request.promptText());
		}
		String key = cacheKey(keyId, request);
		if (vector.length > 0) {
			vectorStore.put(semanticPartition(keyId, request), key, vector);
		}
		sideCar.submit(() -> cache.put(key, response));
	}

	@Override
	public void finish(GatewayContext context, boolean success) {
		if (!properties.isGovernanceEnabled()) {
			return;
		}
		sideCar.submit(() -> {
			int reserved = context.reservedTokens();
			int prompt = context.promptTokens();
			int completion = context.completionTokens();
			// Reconcile the admission pre-charge with what was actually used.
			// Failures / aborts / cache hits never consumed upstream capacity.
			if (!success || context.cached()) {
				limiter.refund(context.keyId(), reserved);
			}
			else {
				int delta = prompt + completion - reserved;
				if (delta > 0) {
					// Best effort: the request has already been served, so we
					// cannot retroactively reject it -- just charge the bucket.
					limiter.tryAcquire(context.keyId(), delta);
				}
				else if (delta < 0) {
					limiter.refund(context.keyId(), -delta);
				}
			}
			GatewayProperties.ModelLimit modelLimit = modelLimitFor(context.model());
			if (modelLimit != null && modelLimit.getTokensPerSecond() > 0) {
				String modelBucket = "model:" + context.model();
				if (!success || context.cached()) {
					limiter.refund(modelBucket, reserved);
				}
				else {
					int delta = prompt + completion - reserved;
					if (delta > 0) {
						limiter.tryAcquire(modelBucket, delta, modelLimit.getTokensPerSecond(),
								modelLimit.getBurst() > 0 ? modelLimit.getBurst() : modelLimit.getTokensPerSecond());
					}
					else if (delta < 0) {
						limiter.refund(modelBucket, -delta);
					}
				}
			}
			ledger.settle(context.reservationId(),
					new UsageLedger.UsageRecord(context.keyId(), context.model(), prompt, completion,
							context.latencyMs(), success, Instant.now(), reserved),
					success || context.cached() || context.upstreamNanos() < 0);
			// v13 $ settlement: failures and cache hits book nothing; a served
			// request books its real token cost at catalog prices.
			// A submitted request may have cost money even when delivery failed.
			boolean canSettle = success || context.cached() || context.upstreamNanos() < 0;
			if (canSettle && budgets != null && context.groupBudgetReservedMicroUsd() > 0) {
				var groupPrice = catalog == null ? null : catalog.find(context.model()).orElse(null);
				long groupActual = success && !context.cached()
						? io.github.leohou0430.llmgw.governance.QuotaMath.costMicroUsd(prompt, completion, groupPrice)
						: 0;
				budgets.settle("group:" + context.group(), context.groupBudgetReservedMicroUsd(), groupActual);
			}
			if (canSettle && budgets != null && context.budgetReservedMicroUsd() > 0) {
				var price = catalog == null ? null : catalog.find(context.model()).orElse(null);
				long actual = success && !context.cached()
						? io.github.leohou0430.llmgw.governance.QuotaMath.costMicroUsd(prompt, completion, price) : 0;
				budgets.settle(context.keyId(), context.budgetReservedMicroUsd(), actual);
			}
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("model", context.model());
			details.put("route", context.routeId());
			details.put("protocol", context.kind().wireName());
			details.put("stream", context.stream());
			details.put("units", context.units());
			details.put("cached", context.cached());
			details.put("promptTokens", prompt);
			details.put("completionTokens", completion);
			long ttft = context.ttftNanos();
			long upstream = context.upstreamNanos();
			long overhead = context.gatewayOverheadNanos();
			details.put("gatewayOverheadMs", GatewayContext.millis(overhead));
			if (ttft >= 0) {
				details.put("ttftMs", GatewayContext.millis(ttft));
			}
			if (upstream >= 0) {
				details.put("upstreamMs", GatewayContext.millis(upstream));
			}
			if (!context.requestId().isBlank()) {
				details.put("requestId", context.requestId());
			}
			// Chapter-9 §9.5.3/§9.4.5: the structured judgment + attribution
			// chain (task → session → turn → call → attempt). Missing
			// attribution stays unattributed; the gateway never fabricates one.
			details.put("decision", "allow");
			details.put("executionStatus", success ? "succeeded" : "failed");
			details.put("usageStatus", usageStatus(context, success));
			details.put("policyVersion", context.policyVersion() > 0 ? context.policyVersion() : policyVersion);
			if (!context.tenantId().isBlank()) {
				details.put("tenantId", context.tenantId());
			}
			if (!context.projectId().isBlank()) {
				details.put("projectId", context.projectId());
			}
			if (!context.sessionId().isBlank()) {
				details.put("sessionId", context.sessionId());
			}
			if (!context.turnId().isBlank()) {
				details.put("turnId", context.turnId());
			}
			if (!context.taskId().isBlank()) {
				details.put("taskId", context.taskId());
			}
			if (!context.billingCallId().isBlank()) {
				details.put("callId", context.billingCallId());
			}
			if (!context.finalAttemptId().isBlank()) {
				details.put("attemptId", context.finalAttemptId());
			}
			if (context.attemptCount() > 0) {
				details.put("attempts", context.attemptCount());
			}
			audit.emit(new AuditSink.AuditEvent(Instant.now(), context.keyId(),
					success ? "request.completed" : "request.failed", details));
			// Chapter-9 §9.6.3: usage provenance as a first-class, bounded tag
			// (reported | estimated | unknown) — never a zero for missing usage.
			metrics.count("llmgw.usage", "source", usageSource(context, success), "engine", engineLabel());
			io.github.leohou0430.llmgw.core.governance.GenAiSpanSink sink = genAiSink;
			if (sink != null) {
				try {
					sink.accept(new io.github.leohou0430.llmgw.core.governance.GenAiSpanSink.GenAiSpan(context.model(),
							Math.max(0, prompt), Math.max(0, completion), usageSource(context, success),
							context.stream(), success));
				}
				catch (RuntimeException observationFailure) {
					// 观测失败不影响结算与审计（观测是旁路，不是账本的一部分）。
				}
			}
			metrics.latency("llmgw.latency", context.latencyMs());
			// v10: the three AI-gateway timings are recorded off the hot path.
			// Upstream time is a full body for JSON and time-to-first-byte for SSE.
			String routeTag = context.routeId().isBlank() ? "none" : context.routeId();
			if (ttft >= 0) {
				metrics.latencyNanos("llmgw.ttft", ttft, "engine", engineLabel(), "route", routeTag);
			}
			if (upstream >= 0) {
				metrics.latencyNanos("llmgw.upstream", upstream, "engine", engineLabel(), "route", routeTag, "mode",
						context.stream() ? "sse" : "json");
			}
			metrics.latencyNanos("llmgw.overhead", overhead, "engine", engineLabel());
			if (!success) {
				metrics.count("llmgw.errors");
			}
			if (requestLog != null) {
				long spend = success && !context.cached() && budgets != null
						? io.github.leohou0430.llmgw.governance.QuotaMath.costMicroUsd(prompt, completion,
								catalog == null ? null : catalog.find(context.model()).orElse(null))
						: 0;
				long ttftN = context.ttftNanos();
				requestLog.log(new RequestLogSink.RequestLog(Instant.now(), context.requestId(), context.keyId(),
						context.model(), context.routeId(), context.kind().wireName(), context.stream(),
						context.cached(), prompt, completion, context.latencyMs(),
						ttftN >= 0 ? Math.round(GatewayContext.millis(ttftN)) : -1, success, spend, ""));
			}
		});
	}

	@Override
	public Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("governanceEnabled", properties.isGovernanceEnabled());
		out.put("usage", ledger.snapshot());
		out.put("cacheSize", cache.size());
		if (budgets != null) {
			// v13: per-key committed spend plus a group rollup (W3.2) so the
			// admin surface can answer "which team burned how much".
			Map<String, Long> spend = budgets.spendMicroUsd();
			out.put("spendMicroUsd", spend);
			Map<String, String> groupOf = new LinkedHashMap<>();
			for (ApiKeyRegistry.ApiKey key : apiKeys.all()) {
				groupOf.put(key.id(), key.group());
			}
			Map<String, Long> byGroup = new LinkedHashMap<>();
			spend.forEach((keyId, microUsd) -> byGroup.merge(groupOf.getOrDefault(keyId, ""), microUsd, Long::sum));
			out.put("spendMicroUsdByGroup", byGroup);
		}
		out.put("recentAudit", audit.recent(20));
		return out;
	}

	/**
	 * Whole-request fingerprint; model + prompt alone is NOT a safe cache key. Streaming
	 * requests carry no precomputed fingerprint (they are never cached); if a custom
	 * chain ever asks for their key, compute the same full-body hash here rather than
	 * falling back to a weaker prompt-only key.
	 */
	/**
	 * B2: the PRIMARY cache key is always the deterministic fingerprint — put and get
	 * MUST agree. Semantic similarity is a separate lookup layer: lookupCache embeds →
	 * vectorStore.findSimilar → reads the similar entry's fingerprint key. This fixes the
	 * v13-W4.2 bug where lookupCache used a signature key but cache() used a fingerprint
	 * key (different keys → every semantic hit was a miss).
	 */
	private String cacheKey(String keyId, ParsedRequest request) {
		String fingerprint = request.cacheFingerprint();
		if (fingerprint == null || fingerprint.isBlank()) {
			fingerprint = ProtocolCodec.fingerprint(request.rawJson());
		}
		return ProtocolCodec.fingerprint(keyId) + ":" + request.cacheNamespace() + ":" + request.kind().wireName() + ":"
				+ fingerprint;
	}

	/** Only a single plain user input may vary; preserve every other request field. */
	private String semanticPartition(String keyId, ParsedRequest request) {
		var root = ProtocolCodec.tryParse(request.rawJson());
		if (root instanceof tools.jackson.databind.node.ObjectNode object) {
			var input = object.get("input");
			var messages = object.get("messages");
			if (request.kind() == io.github.leohou0430.llmgw.core.protocol.ProtocolKind.RESPONSES && input != null
					&& input.isTextual()) {
				object.put("input", "");
			}
			else if ((request.kind() == io.github.leohou0430.llmgw.core.protocol.ProtocolKind.CHAT
					|| request.kind() == io.github.leohou0430.llmgw.core.protocol.ProtocolKind.MESSAGES)
					&& messages != null && messages.isArray() && messages.size() == 1
					&& messages.get(0) instanceof tools.jackson.databind.node.ObjectNode message
					&& (message.path("role").asText("").isEmpty() || "user".equals(message.path("role").asText("")))
					&& message.path("content").isTextual()) {
				message.put("content", "");
			}
			else {
				return cacheKey(keyId, request);
			}
			return ProtocolCodec.fingerprint(keyId) + ":" + request.cacheNamespace() + ":" + request.kind().wireName()
					+ ":" + ProtocolCodec.fingerprint(ProtocolCodec.toJson(object));
		}
		return cacheKey(keyId, request);
	}

	/**
	 * v13 request-log entry + Chapter-9 §9.5.3 structured admission-denial audit: every
	 * rejection records WHICH policy version denied it and why -- the decision matrix
	 * must stay explainable, not a black box. Denials were previously only visible in the
	 * request log; now the audit trail carries them with the same weight as allows.
	 */
	private void logDeny(ParsedRequest request, String keyId, String reason, int status) {
		final String keyIdFinal = keyId;
		final ParsedRequest parsed = request;
		final String reasonFinal = reason;
		final long version = policyVersion;
		sideCar.submit(() -> {
			if (requestLog != null) {
				requestLog.log(new RequestLogSink.RequestLog(Instant.now(), "", keyIdFinal, parsed.model(), "",
						parsed.kind().wireName(), parsed.stream(), false, 0, 0, 0, -1, false, 0, reasonFinal));
			}
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("decision", "deny");
			details.put("status", status);
			details.put("reason", reasonFinal);
			details.put("policyVersion", version);
			details.put("model", parsed.model());
			details.put("protocol", parsed.kind().wireName());
			details.put("stream", parsed.stream());
			audit.emit(new AuditSink.AuditEvent(Instant.now(), keyIdFinal, "admission.denied", details));
		});
	}

	/**
	 * Chapter-9 §9.2.5/§9.6.3 usage provenance: the upstream usage frame is
	 * authoritative; missing usage never books as zero — it stays estimated (served) or
	 * unknown (submitted, outcome unverified).
	 */
	private static String usageSource(GatewayContext context, boolean success) {
		boolean reported = context.reportedPromptTokensRaw() >= 0 && context.reportedCompletionTokensRaw() >= 0;
		if (reported) {
			return "reported";
		}
		if (!success && context.upstreamNanos() >= 0 && !context.cached()) {
			return "unknown";
		}
		return "estimated";
	}

	/**
	 * Ledger-facing status: settled / estimated / pending_reconciliation / not_submitted.
	 */
	private static String usageStatus(GatewayContext context, boolean success) {
		if (context.cached() || context.upstreamNanos() < 0) {
			return "not_submitted";
		}
		boolean reported = context.reportedPromptTokensRaw() >= 0 && context.reportedCompletionTokensRaw() >= 0;
		if (success) {
			return reported ? "settled" : "estimated";
		}
		return reported ? "settled" : "pending_reconciliation";
	}

	private static String bearer(String header) {
		if (header == null) {
			return "";
		}
		String value = header.trim();
		if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return value.substring(7).trim();
		}
		return value;
	}

}
