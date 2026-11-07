package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.SnapshotStore;
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
    private final OpenAiSdkVerifier sdkVerifier;
    private final ObjectProvider<MeterRegistry> meterRegistries;
    private final ObjectProvider<RouteConfigSource> routeSources;
    private final GatewayProperties properties;
    private final String adminToken;

    public AdminController(GovernanceChain chain, SnapshotStore snapshots, Router router,
                           ApiKeyRegistry apiKeys, OpenAiSdkVerifier sdkVerifier,
                           ObjectProvider<MeterRegistry> meterRegistries,
                           ObjectProvider<RouteConfigSource> routeSources,
                           GatewayProperties properties) {
        this.chain = chain;
        this.snapshots = snapshots;
        this.router = router;
        this.apiKeys = apiKeys;
        this.sdkVerifier = sdkVerifier;
        this.meterRegistries = meterRegistries;
        this.routeSources = routeSources;
        this.properties = properties;
        this.adminToken = properties.getAdminToken() == null ? "" : properties.getAdminToken();
    }

    /**
     * Write shape for POST /admin/routes/reload. apiKey is optional and is
     * NEVER echoed back (it can be the upstream provider's secret).
     */
    public record RouteSpec(String id, String model, String targetModel, String targetUrl,
                            String apiKey, Integer weight, List<String> fallbacks) {
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
            routes.add(new Route(spec.id(), model,
                    spec.targetModel() == null ? "" : spec.targetModel(),
                    url, key, weight, fallbacks));
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
            item.put("model", route.model());
            item.put("targetModel", route.targetModel());
            item.put("targetUrl", route.targetUrl());
            item.put("weight", route.weight());
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
        if (adminToken.isBlank()) {
            return; // local demo mode
        }
        byte[] expected = adminToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin token required");
        }
    }
}
