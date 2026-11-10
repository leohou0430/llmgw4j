package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.SnapshotStore;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.RouteConfigSource;
import io.github.leohou0430.llmgw.core.routing.RouteTable;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.provider.openai.OpenAiSdkVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Management surface. Two v5 hardening changes:
 * <ul>
 *   <li>{@code /admin/routes} no longer serializes the Route record directly.
 *       That record carries {@code apiKey}, which is the UPSTREAM PROVIDER KEY
 *       ({@code ${DASHSCOPE_API_KEY}}) -- an unauthenticated GET used to hand
 *       it to anyone who could reach the port. Only a boolean is exposed now.</li>
 *   <li>An optional shared admin token ({@code gateway.admin-token}, env
 *       {@code LLMGW_ADMIN_TOKEN}) gates every mutating / sensitive endpoint.
 *       Empty by default for the local single-process demo; production MUST set
 *       it (or put /admin behind a private listener / gateway).</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GovernanceChain chain;
    private final SnapshotStore snapshots;
    private final Router router;
    private final ApiKeyRegistry apiKeys;
    private final ObjectProvider<io.github.leohou0430.llmgw.core.governance.KeyAdminService> keyAdmin;
    private final ObjectProvider<io.github.leohou0430.llmgw.core.governance.RequestLogSink> requestLogs;
    private final ObjectProvider<io.github.leohou0430.llmgw.governance.inmemory.DynamicGovernanceSettings> governanceSettings;
    private final ObjectProvider<io.github.leohou0430.llmgw.core.governance.SemanticCache> semanticCaches;
    private final ObjectProvider<io.github.leohou0430.llmgw.core.governance.AuditSink> auditSink;
    private final OpenAiSdkVerifier sdkVerifier;
    private final ObjectProvider<MeterRegistry> meterRegistries;
    private final ObjectProvider<RouteConfigSource> routeSources;
    private final GatewayProperties properties;
    private final String adminToken;
    private final String adminReadToken;

    public AdminController(GovernanceChain chain, SnapshotStore snapshots, Router router,
                           ApiKeyRegistry apiKeys,
                           ObjectProvider<io.github.leohou0430.llmgw.core.governance.KeyAdminService> keyAdmin,
                           ObjectProvider<io.github.leohou0430.llmgw.core.governance.RequestLogSink> requestLogs,
                           ObjectProvider<io.github.leohou0430.llmgw.governance.inmemory.DynamicGovernanceSettings> governanceSettings,
                           ObjectProvider<io.github.leohou0430.llmgw.core.governance.SemanticCache> semanticCaches,
                           ObjectProvider<io.github.leohou0430.llmgw.core.governance.AuditSink> auditSink,
                           OpenAiSdkVerifier sdkVerifier,
                           ObjectProvider<MeterRegistry> meterRegistries,
                           ObjectProvider<RouteConfigSource> routeSources,
                           GatewayProperties properties) {
        this.chain = chain;
        this.snapshots = snapshots;
        this.router = router;
        this.apiKeys = apiKeys;
        this.keyAdmin = keyAdmin;
        this.requestLogs = requestLogs;
        this.governanceSettings = governanceSettings;
        this.semanticCaches = semanticCaches;
        this.auditSink = auditSink;
        this.sdkVerifier = sdkVerifier;
        this.meterRegistries = meterRegistries;
        this.routeSources = routeSources;
        this.properties = properties;
        this.adminToken = properties.getAdminToken() == null ? "" : properties.getAdminToken();
        this.adminReadToken = properties.getAdminReadToken() == null
                ? "" : properties.getAdminReadToken();
    }

    /** Audit export accepts either credential; only a fully unconfigured demo is open. */
    private boolean isReadAuthorized(String token) {
        return (adminToken.isBlank() && adminReadToken.isBlank())
                || tokenMatches(adminToken, token) || tokenMatches(adminReadToken, token);
    }

    private static boolean tokenMatches(String expected, String supplied) {
        return !expected.isBlank() && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write shape for POST /admin/routes/reload. apiKey is optional and is
     * NEVER echoed back (it can be the upstream provider's secret). protocol
     * pins the route to one endpoint protocol; ""/"*" match every protocol.
     */
    public record RouteSpec(String id, String protocol, String model, String targetModel,
                            String targetUrl, String apiKey, String provider, Integer weight,
                            List<String> fallbacks) {
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        return chain.snapshot();
    }

    @PostMapping("/snapshot/save")
    public Map<String, Object> save(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        snapshots.save();
        return Map.of("saved", true, "json", snapshots.exportJson());
    }

    /**
     * Restore is REPLACE semantics now (see InMemoryUsageLedger.load), so
     * calling this repeatedly no longer multiplies the counters and starves the
     * quota. Still idempotent-unsafe with live traffic -- keep it behind the
     * admin token.
     */
    @PostMapping("/snapshot/load")
    public Map<String, Object> load(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        snapshots.load();
        return Map.of("loaded", true);
    }

    @GetMapping("/routes")
    public ResponseEntity<Object> routes(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        RouteConfigSource source = routeSources.getIfAvailable();
        RouteTable table = source == null ? null : source.current();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (table != null) {
            builder = builder.header("X-LLMGW-Config-Version", Long.toString(table.version()));
        }
        return builder.body(view(router.all()));
    }

    /**
     * v10 hot config: build and validate a COMPLETE new table before swapping
     * one atomic reference. In-flight requests keep the Route object they
     * already resolved, so long-lived SSE streams are never drained or
     * re-routed mid-answer. Read-only sources report 409 instead of no-op.
     */
    @PostMapping("/routes/reload")
    public Map<String, Object> reloadRoutes(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody(required = false) List<RouteSpec> specs) {
        requireAdmin(token);
        RouteConfigSource source = routeSources.getIfAvailable();
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no route config source available");
        }
        if (specs == null || specs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routes must not be empty");
        }
        String defaultUrl = properties.getUpstream().getBaseUrl();
        String defaultKey = properties.getUpstream().getApiKey();
        List<Route> routes = new ArrayList<>(specs.size());
        Set<String> ids = new HashSet<>();
        for (RouteSpec spec : specs) {
            if (spec == null || spec.id() == null || spec.id().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "route id must not be blank");
            }
            if (!ids.add(spec.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "duplicate route id: " + spec.id());
            }
            String model = spec.model() == null || spec.model().isBlank() ? "*" : spec.model();
            String protocol = spec.protocol() == null ? "" : spec.protocol().trim();
            if (!ProtocolKind.isConfigValue(protocol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "protocol must be chat|responses|messages|images|videos|*: " + spec.id());
            }
            String url = spec.targetUrl() == null || spec.targetUrl().isBlank()
                    ? defaultUrl : spec.targetUrl();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "targetUrl must be http(s): " + spec.id());
            }
            String key = spec.apiKey() == null || spec.apiKey().isBlank()
                    ? defaultKey : spec.apiKey();
            int weight = spec.weight() == null ? 1 : Math.max(1, spec.weight());
            List<String> fallbacks = spec.fallbacks() == null
                    ? List.of() : List.copyOf(spec.fallbacks());
            routes.add(new Route(spec.id(),
                    protocol.isBlank() ? "*" : protocol, model,
                    spec.targetModel() == null ? "" : spec.targetModel(),
                    url, key, spec.provider() == null ? "" : spec.provider(),
                    weight, fallbacks));
        }
        for (Route route : routes) {
            for (String id : route.fallbackIds()) {
                if (!ids.contains(id)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "unknown fallback id: " + id);
                }
            }
        }
        long version = source.replace(routes);
        if (version < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "route config source is read-only");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", true);
        out.put("version", version);
        out.put("routes", view(routes));
        return out;
    }

    /** apiKey is a provider secret: only its presence is ever exposed. */
    private static List<Map<String, Object>> view(List<Route> routes) {
        List<Map<String, Object>> safe = new ArrayList<>(routes.size());
        for (Route route : routes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", route.id());
            item.put("protocol", route.protocol());
            item.put("model", route.model());
            item.put("targetModel", route.targetModel());
            item.put("targetUrl", route.targetUrl());
            item.put("weight", route.weight());
            item.put("provider", route.provider());
            item.put("fallbackIds", route.fallbackIds());
            item.put("apiKeyConfigured", configured(route.apiKey()));
            safe.add(item);
        }
        return safe;
    }

    @GetMapping("/keys")
    public Object keys(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        return apiKeys.all();
    }

    /**
     * v13 key lifecycle. The plaintext token appears in THIS response only and
     * is persisted to the key store; metadata endpoints never echo it. A
     * registry without {@code KeyAdminService} (custom read-only packs)
     * answers 501.
     */
    public record CreateKeySpec(String name, String group, Long budgetMicroUsd, Long quotaTokens,
                                List<String> allowedModels, Long ttlSeconds,
                                Map<String, String> metadata) {
    }

    @PostMapping("/keys")
    public Object createKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                            @RequestBody CreateKeySpec spec) {
        requireAdmin(token);
        io.github.leohou0430.llmgw.core.governance.KeyAdminService admin =
                keyAdmin.getIfAvailable();
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "registry does not support key management");
        }
        var created = admin.create(spec.name(),
                spec.group(),
                spec.budgetMicroUsd() == null ? 0 : spec.budgetMicroUsd(),
                spec.quotaTokens() == null
                        ? 1_000_000
                        : spec.quotaTokens(),
                spec.allowedModels() == null ? Set.of() : Set.copyOf(spec.allowedModels()),
                spec.ttlSeconds(),
                spec.metadata() == null ? Map.of() : spec.metadata());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.key().id());
        out.put("name", created.key().name());
        out.put("token", created.token());
        out.put("group", created.key().group());
        out.put("budgetMicroUsd", created.key().budgetMicroUsd());
        out.put("metadata", created.key().metadata());
        out.put("quotaTokens", created.key().quotaTokens());
        out.put("allowedModels", created.key().allowedModels());
        out.put("expiresAtEpochMs", created.key().expiresAtEpochMs());
        return out;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/keys/{id}")
    public Object revokeKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                            @org.springframework.web.bind.annotation.PathVariable("id") String id) {
        requireAdmin(token);
        io.github.leohou0430.llmgw.core.governance.KeyAdminService admin =
                keyAdmin.getIfAvailable();
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "registry does not support key management");
        }
        boolean revoked = admin.revoke(id);
        if (!revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown key id " + id);
        }
        return Map.of("revoked", true, "id", id);
    }

    /**
     * B1 key rotation: mint a NEW token for the same key; the old token stays
     * valid for the overlap window (gateway.auth.rotate-overlap-seconds or the
     * explicit value here) so clients migrate without downtime.
     */
    public record RotateSpec(Integer overlapSeconds) {
    }

    @org.springframework.web.bind.annotation.PostMapping("/keys/{id}/rotate")
    public Object rotateKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                            @org.springframework.web.bind.annotation.PathVariable("id") String id,
                            @RequestBody(required = false) RotateSpec spec) {
        requireAdmin(token);
        io.github.leohou0430.llmgw.core.governance.KeyAdminService admin =
                keyAdmin.getIfAvailable();
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "registry does not support key management");
        }
        var rotated = admin.rotate(id,
                spec == null || spec.overlapSeconds() == null
                        ? properties.getAuth().getRotateOverlapSeconds()
                        : spec.overlapSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rotated.key().id());
        out.put("token", rotated.token());
        out.put("overlapSeconds", spec == null || spec.overlapSeconds() == null
                ? properties.getAuth().getRotateOverlapSeconds() : spec.overlapSeconds());
        return out;
    }

    /** Optional real-provider connectivity check; never called on request path. */
    @GetMapping("/verify")
    public Map<String, Object> verify(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sdkValidation", sdkVerifier.validate("qwen-plus", "ping"));
        out.put("connectivity", sdkVerifier.verify());
        return out;
    }

    /**
     * v13 request log (newest first). A bounded debugging surface: capacity
     * comes from {@code gateway.observability.request-log-capacity}.
     */
    @GetMapping("/requests")
    public Object requests(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int limit) {
        requireAdmin(token);
        var sink = requestLogs.getIfAvailable();
        if (sink == null) {
            return List.of();
        }
        return sink.recent(Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * v13 minimal read-only console (single static page over the /admin JSON).
     * The page itself re-prompts for the admin token in the browser; this
     * endpoint stays ungated so the HTML can load, every DATA call is gated.
     */
    @GetMapping(value = "/console", produces = "text/html")
    public String console() throws java.io.IOException {
        try (var in = getClass().getResourceAsStream("/console/index.html")) {
            return in == null ? "<html><body>console asset missing</body></html>"
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** B5: full audit-ring export (JSON). Read-token authorized. */
    @GetMapping("/audit/export")
    public Object exportAudit(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestHeader(value = "X-Admin-Read-Token", required = false) String readToken) {
        if (!isReadAuthorized(readToken != null && !readToken.isBlank() ? readToken : token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin auth required");
        }
        var sink = auditSink.getIfAvailable();
        if (sink == null) {
            return List.of();
        }
        return sink.recent(5000);
    }

    /** B8: cache invalidation (in-memory ring, Redis keyspace, or any store). */
    @org.springframework.web.bind.annotation.DeleteMapping("/cache")
    public Object invalidateCache(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        var cache = semanticCaches.getIfAvailable();
        if (cache == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "no semantic cache bean");
        }
        cache.invalidateAll();
        return Map.of("invalidated", true);
    }

    /**
     * W3.5 governance hot reload: swap rate-limit / cache / circuit settings
     * atomically without a restart. Null fields keep their current value.
     */
    public record GovernanceSpec(Long rateLimitTokensPerSecond, Long rateLimitBurst,
                                 Integer cacheMaxSize, Integer cacheTtlSeconds,
                                 Integer circuitFailureThreshold, Integer circuitCooldownMs) {
    }

    @PostMapping("/governance")
    public Object reloadGovernance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody GovernanceSpec spec) {
        requireAdmin(token);
        var settings = governanceSettings.getIfAvailable();
        if (settings == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "no dynamic governance settings bean");
        }
        var snapshot = settings.update(spec.rateLimitTokensPerSecond(), spec.rateLimitBurst(),
                spec.cacheMaxSize(), spec.cacheTtlSeconds(),
                spec.circuitFailureThreshold(), spec.circuitCooldownMs());
        return Map.of("version", settings.version(), "snapshot", snapshot);
    }

    /**
     * Aggregated llmgw.* Micrometer meters: denied-reason counters, cache
     * hit/miss, per-route request counts, circuit events and latency timer.
     * Reading Micrometer here (instead of duplicating bookkeeping) means any
     * MeterRegistry backend -- Prometheus, OTLP, Simple -- works unchanged,
     * and swapping the governance implementation still feeds the same view.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        Map<String, Object> out = new LinkedHashMap<>();
        MeterRegistry registry = meterRegistries.getIfAvailable();
        if (registry == null) {
            out.put("available", false);
            return out;
        }
        List<Map<String, Object>> counters = new java.util.ArrayList<>();
        List<Map<String, Object>> timers = new java.util.ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("llmgw.")) {
                continue;
            }
            String tags = meter.getId().getTags().stream()
                    .map(t -> t.getKey() + "=" + t.getValue())
                    .sorted()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            if (meter instanceof Counter counter) {
                counters.add(Map.of("name", name, "tags", tags, "value", counter.count()));
            } else if (meter instanceof Timer timer) {
                timers.add(Map.of(
                        "name", name, "tags", tags,
                        "count", timer.count(),
                        "p50ms", round1(timer.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS)),
                        "p99ms", round1(timer.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS)),
                        "maxMs", round1(timer.max(java.util.concurrent.TimeUnit.MILLISECONDS))));
            }
        }
        out.put("available", true);
        out.put("counters", counters);
        out.put("timers", timers);
        return out;
    }

    private static double round1(double v) {
        // A Prometheus-backed Timer has no client-side percentile values;
        // returning NaN would put a non-standard token in the JSON body.
        return Double.isFinite(v) ? Math.round(v * 10.0) / 10.0 : 0.0;
    }

    private static boolean configured(String key) {
        return key != null && !key.isBlank() && !key.startsWith("${");
    }

    private void requireAdmin(String supplied) {
        if (adminToken.isBlank() && adminReadToken.isBlank()) {
            return; // local demo mode
        }
        if (!tokenMatches(adminToken, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin token required");
        }
    }
}
