package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.SemanticCache;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The ONE governance implementation shared by both engines.
 * Hot path: authentication, quota and token rate limiting (pure in-memory atomics).
 * Side car: ledger writes, audit events, metrics, cache writes (virtual threads).
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
    /**
     * Engine label is resolved once on first use, not in the constructor.
     * {@code EngineKindEnvironmentPostProcessor} publishes the active engine
     * during environment preparation, so the value is already present by the
     * time this bean is built; caching only a <em>resolved</em> label means a
     * custom composition root that registers the engine later can never pin
     * {@code unknown} for the process lifetime. The volatile read is far
     * cheaper than the synchronized Properties lookup it replaces.
     */
    private volatile String engineLabel;

    public DefaultGovernanceChain(ApiKeyRegistry apiKeys,
                                  TokenLimiter limiter,
                                  UsageLedger ledger,
                                  SemanticCache cache,
                                  AuditSink audit,
                                  ExecutorService sideCar,
                                  GovernanceMetrics metrics,
                                  GatewayProperties properties) {
        this.apiKeys = apiKeys;
        this.limiter = limiter;
        this.ledger = ledger;
        this.cache = cache;
        this.audit = audit;
        this.sideCar = sideCar;
        this.metrics = metrics;
        this.properties = properties;
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

    @Override
    public GatewayDecision enter(String authorizationHeader, ParsedRequest request, Route route) {
        if (!properties.isGovernanceEnabled()) {
            return GatewayDecision.allow("anonymous", request, route);
        }
        String token = bearer(authorizationHeader);
        ApiKeyRegistry.ApiKey key = apiKeys.find(token).orElse(null);
        if (key == null) {
            metrics.count("llmgw.denied", "reason", "invalid_key");
            return GatewayDecision.deny(401, "invalid api key");
        }
        if (!key.allowedModels().isEmpty() && !key.allowedModels().contains(request.model())) {
            metrics.count("llmgw.denied", "reason", "model_not_allowed");
            return GatewayDecision.deny(403, "model not allowed for this key");
        }
        int reserved = Math.max(1, request.estimatedTokens());
        if (!limiter.tryAcquire(key.id(), reserved)) {
            metrics.count("llmgw.denied", "reason", "rate_limited");
            return GatewayDecision.deny(429, "token rate limit exceeded");
        }
        // Atomic reservation includes in-flight requests. A plain
        // used()+estimate>quota check lets N concurrent callers all pass.
        if (!ledger.tryReserve(key.id(), reserved, key.quotaTokens())) {
            limiter.refund(key.id(), reserved);
            metrics.count("llmgw.denied", "reason", "quota_exceeded");
            return GatewayDecision.deny(429, "token quota exceeded");
        }
        metrics.count("llmgw.requests", "engine", engineLabel(),
                "route", route == null ? "none" : route.id());
        return GatewayDecision.allow(key.id(), request, route);
    }

    @Override
    public Optional<String> lookupCache(String keyId, ParsedRequest request) {
        if (!properties.isGovernanceEnabled() || request.stream()) {
            return Optional.empty();
        }
        Optional<String> hit = cache.get(cacheKey(keyId, request));
        metrics.count("llmgw.cache", "result", hit.isPresent() ? "hit" : "miss");
        return hit;
    }

    @Override
    public void cache(String keyId, ParsedRequest request, String response) {
        if (!properties.isGovernanceEnabled() || request.stream()
                || !ProtocolCodec.isCacheableResponse(response)) {
            return;
        }
        String key = cacheKey(keyId, request);
        sideCar.submit(() -> cache.put(key, response));
    }

    /**
     * Single-parse fast path: the controller already built the JsonNode tree
     * and derived this verdict from it. Without this override the interface
     * default would forward to the 3-arg method, which re-parses the body --
     * silently undoing the optimization this overload exists for.
     */
    @Override
    public void cache(String keyId, ParsedRequest request, String response, boolean cacheable) {
        if (!properties.isGovernanceEnabled() || request.stream() || !cacheable) {
            return;
        }
        String key = cacheKey(keyId, request);
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
            } else {
                int delta = prompt + completion - reserved;
                if (delta > 0) {
                    // Best effort: the request has already been served, so we
                    // cannot retroactively reject it -- just charge the bucket.
                    limiter.tryAcquire(context.keyId(), delta);
                } else if (delta < 0) {
                    limiter.refund(context.keyId(), -delta);
                }
            }
            ledger.record(new UsageLedger.UsageRecord(context.keyId(), context.model(),
                    prompt, completion,
                    context.latencyMs(), success, Instant.now(), reserved));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("model", context.model());
            details.put("route", context.routeId());
            details.put("stream", context.stream());
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
            audit.emit(new AuditSink.AuditEvent(Instant.now(), context.keyId(),
                    success ? "request.completed" : "request.failed", details));
            metrics.latency("llmgw.latency", context.latencyMs());
            // v10: the three AI-gateway timings are recorded off the hot path.
            // Upstream time is a full body for JSON and time-to-first-byte for SSE.
            String routeTag = context.routeId().isBlank() ? "none" : context.routeId();
            if (ttft >= 0) {
                metrics.latencyNanos("llmgw.ttft", ttft,
                        "engine", engineLabel(), "route", routeTag);
            }
            if (upstream >= 0) {
                metrics.latencyNanos("llmgw.upstream", upstream,
                        "engine", engineLabel(), "route", routeTag,
                        "mode", context.stream() ? "sse" : "json");
            }
            metrics.latencyNanos("llmgw.overhead", overhead, "engine", engineLabel());
            if (!success) {
                metrics.count("llmgw.errors");
            }
        });
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("governanceEnabled", properties.isGovernanceEnabled());
        out.put("usage", ledger.snapshot());
        out.put("cacheSize", cache.size());
        out.put("recentAudit", audit.recent(20));
        return out;
    }

    /**
     * Whole-request fingerprint; model + prompt alone is NOT a safe cache key.
     * Streaming requests carry no precomputed fingerprint (they are never
     * cached); if a custom chain ever asks for their key, compute the same
     * full-body hash here rather than falling back to a weaker prompt-only key.
     */
    private static String cacheKey(String keyId, ParsedRequest request) {
        String fingerprint = request.cacheFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = ProtocolCodec.fingerprint(request.rawJson());
        }
        return keyId + ":" + fingerprint;
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
