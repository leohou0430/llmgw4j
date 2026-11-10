package io.github.leohou0430.llmgw.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties implements org.springframework.beans.factory.InitializingBean {

    private boolean production;

    public boolean isProduction() { return production; }
    public void setProduction(boolean value) { this.production = value; }

    @Override
    public void afterPropertiesSet() {
        if (!production) {
            return;
        }
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException("production requires gateway.admin-token");
        }
        if (adminToken.equals(adminReadToken)) {
            throw new IllegalStateException("production requires distinct full and read-only admin credentials");
        }
        if (!governanceEnabled) {
            throw new IllegalStateException("production requires governance-enabled=true");
        }
        if (apiKeys.isEmpty() && auth.getJwtSecret().isBlank()) {
            throw new IllegalStateException("production requires API keys or JWT authentication");
        }
        var ids = new java.util.HashSet<String>();
        var tokens = new java.util.HashSet<String>();
        for (ApiKeyDef key : apiKeys) {
            if (key.getId() == null || key.getId().isBlank() || !ids.add(key.getId())
                    || key.getToken() == null || key.getToken().isBlank()
                    || "sk-demo".equals(key.getToken()) || !tokens.add(key.getToken())) {
                throw new IllegalStateException("production requires unique non-demo API credentials and ids");
            }
        }
        if (apiKeys.stream().anyMatch(key -> key.getBudgetMicroUsd() > 0)
                || groupBudgets.stream().anyMatch(group -> group.getBudgetMicroUsd() > 0)) {
            throw new IllegalStateException("production monetary budgets require a durable, recoverable ledger and cost bounds; unavailable in this implementation");
        }
    }

    private final Upstream upstream = new Upstream();
    private final Governance governance = new Governance();
    private final Mock mock = new Mock();
    private final Videos videos = new Videos();
    private final Headers headers = new Headers();
    private final Observability observability = new Observability();
    private final Guardrails guardrails = new Guardrails();
    private final Cache cache = new Cache();
    /** B4: per-group hard budgets (micro-USD, cumulative spend across keys). */
    private final List<GroupBudget> groupBudgets = new ArrayList<>();
    /** B4: per-model token-bucket overrides (matched like routes: exact > prefix > *). */
    private final List<ModelLimit> modelLimits = new ArrayList<>();
    private List<RouteDef> routes = new ArrayList<>();
    private List<ApiKeyDef> apiKeys = new ArrayList<>();
    private final List<ModelDef> models = new ArrayList<>();
    private boolean governanceEnabled = true;
    /** Hard cap for a request body, protects both data planes from OOM. */
    private long maxRequestBytes = 4 * 1024 * 1024;
    /** Separate cap for buffered upstream responses (JSON and binary). */
    private long maxResponseBytes = 4 * 1024 * 1024;
    private int maxConcurrentRequests = 64;
    /** Empty = admin endpoints open (local demo). Set it in any shared environment. */
    private String adminToken = "";
    /** Read-only token accepted by audit export; other admin calls require the full token. */
    private String adminReadToken = "";
    private final Auth auth = new Auth();
    /**
     * v13 persistence for runtime-created API keys (POST /admin/keys). Blank
     * disables the store; the default keeps a 0600 JSON file under ./data so
     * runtime keys survive restarts.
     */
    private String keysStorePath = "./data/llmgw-keys.json";
    /** B1: ingress auth depth beyond static keys. HS256 JWT, off by default. */
    public static class Auth {
        /** Blank = JWT validation off (static registry keys only). */
        private String jwtSecret = "";
        private String jwtIssuer = "";
        /** Expected JWT recipient; blank preserves deployments without audience validation. */
        private String jwtAudience = "";
        /** Quota given to JWT-authenticated callers (no registry key exists). */
        private long jwtQuotaTokens = 1_000_000;
        /** Overlap window (seconds) during which a rotated-out token still works. */
        private int rotateOverlapSeconds = 300;
        /** true = key store persists SHA-256(token) instead of plaintext (B1). */
        private boolean hashKeysAtRest = true;

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String v) { this.jwtSecret = v == null ? "" : v; }
        public String getJwtIssuer() { return jwtIssuer; }
        public void setJwtIssuer(String v) { this.jwtIssuer = v == null ? "" : v; }
        public String getJwtAudience() { return jwtAudience; }
        public void setJwtAudience(String v) { this.jwtAudience = v == null ? "" : v; }
        public long getJwtQuotaTokens() { return jwtQuotaTokens; }
        public void setJwtQuotaTokens(long v) { this.jwtQuotaTokens = Math.max(0, v); }
        public int getRotateOverlapSeconds() { return rotateOverlapSeconds; }
        public void setRotateOverlapSeconds(int v) { this.rotateOverlapSeconds = Math.max(0, v); }
        public boolean isHashKeysAtRest() { return hashKeysAtRest; }
        public void setHashKeysAtRest(boolean v) { this.hashKeysAtRest = v; }
    }

    public Upstream getUpstream() { return upstream; }
    public Governance getGovernance() { return governance; }
    public Mock getMock() { return mock; }
    public Videos getVideos() { return videos; }
    public Headers getHeaders() { return headers; }
    public Observability getObservability() { return observability; }
    public Guardrails getGuardrails() { return guardrails; }
    public Cache getCache() { return cache; }
    public List<GroupBudget> getGroupBudgets() { return groupBudgets; }
    public List<ModelLimit> getModelLimits() { return modelLimits; }
    public List<RouteDef> getRoutes() { return routes; }
    public void setRoutes(List<RouteDef> routes) { this.routes = routes; }
    public List<ApiKeyDef> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<ApiKeyDef> apiKeys) { this.apiKeys = apiKeys; }
    public List<ModelDef> getModels() { return models; }
    public boolean isGovernanceEnabled() { return governanceEnabled; }
    public void setGovernanceEnabled(boolean governanceEnabled) { this.governanceEnabled = governanceEnabled; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public long getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(long value) { this.maxResponseBytes = Math.max(1, value); }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { this.maxConcurrentRequests = Math.max(1, value); }
    public void setMaxRequestBytes(long v) { this.maxRequestBytes = v; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String v) { this.adminToken = v; }
    public String getAdminReadToken() { return adminReadToken; }
    public void setAdminReadToken(String v) { this.adminReadToken = v; }
    public String getKeysStorePath() { return keysStorePath; }
    public void setKeysStorePath(String v) { this.keysStorePath = v; }
    public Auth getAuth() { return auth; }

    public static class Upstream {
        /** mock | openai-compatible */
        private String type = "mock";
        private String baseUrl = "http://127.0.0.1:9090/mock/v1";
        private String apiKey = "${DASHSCOPE_API_KEY:}";
        /** Whole-request backstop for one upstream call (v12 semantics). */
        private int timeoutMs = 60000;
        /**
         * v13 three-tier timeouts. {@code timeout-ms} stays as the whole-request
         * backstop; the tiers below sharpen it:
         * connect -- TCP connect only;
         * first-byte -- time to response HEADERS on streaming calls (a hung
         * upstream must fail before the 200 is committed, while fallback is
         * still possible);
         * idle-read -- silence between SSE chunks after the stream started
         * (a mid-stream stall is a truncation-in-progress, not a slow model).
         * 0 = disabled; all defaults keep v12 behavior.
         */
        private int connectTimeoutMs = 10_000;
        private int firstByteTimeoutMs = 0;
        private int idleReadTimeoutMs = 0;
        /**
         * SSE keep-alive: inject an SSE comment frame ({@code : keep-alive}) on
         * idle streams so intermediate LBs/proxies with ~60s idle timeouts stop
         * severing long generations. 0 = disabled (default: byte-faithful
         * pass-through injects NOTHING). Comment frames are transport-level:
         * engines exclude them from chunk accounting, TTFT and the first-chunk
         * fallback guard.
         */
        private int heartbeatIntervalMs = 0;
        private final Retry retry = new Retry();

        public Retry getRetry() { return retry; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getFirstByteTimeoutMs() { return firstByteTimeoutMs; }
        public void setFirstByteTimeoutMs(int v) { this.firstByteTimeoutMs = v; }
        public int getIdleReadTimeoutMs() { return idleReadTimeoutMs; }
        public void setIdleReadTimeoutMs(int v) { this.idleReadTimeoutMs = v; }
        public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void setHeartbeatIntervalMs(int v) { this.heartbeatIntervalMs = v; }

        /**
         * Same-route retry before the fallback chain. attempts=1 keeps v12
         * behavior exactly; 5xx/408/429 and transport failures are retryable,
         * other 4xx are not (see ForwardingPolicy.retryable).
         */
        public static class Retry {
            private int attempts = 1;
            private int backoffBaseMs = 200;
            private int backoffMaxMs = 2000;

            public int getAttempts() { return attempts; }
            public void setAttempts(int v) { this.attempts = v; }
            public int getBackoffBaseMs() { return backoffBaseMs; }
            public void setBackoffBaseMs(int v) { this.backoffBaseMs = v; }
            public int getBackoffMaxMs() { return backoffMaxMs; }
            public void setBackoffMaxMs(int v) { this.backoffMaxMs = v; }
        }
        /**
         * &gt;0 = dedicated fixed platform-thread pool for HttpClient sendAsync.
         * 0 would fall back to the JDK default executor, which the 2026-11-07
         * sweep measured as a real bottleneck (SSE -30% throughput at 50 VU).
         * Sweep (8/16/32/64 threads) put the knee at 32: 64 adds nothing and
         * 16 already loses ~8% JSON RPS.
         */
        private int executorThreads = 32;

        public int getExecutorThreads() { return executorThreads; }
        public void setExecutorThreads(int v) { this.executorThreads = v; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Governance {
        private int defaultQuotaTokens = 1_000_000;
        private int rateLimitTokensPerSecond = 10_000;
        private int rateLimitBurst = 20_000;
        private int cacheMaxSize = 1000;
        private int cacheTtlSeconds = 300;
        private String snapshotPath = "./data/llmgw-snapshot.json";
        /**
         * v13: opt-in. When true, the usage ledger is restored from
         * {@code snapshot-path} at startup (POST /admin/snapshot/save keeps it
         * fresh). Stays false by default on purpose: the A/B benchmark arms
         * must always start from a fresh process, and a stale ledger would
         * inject an uncontrolled quota variable into the measurements.
         */
        private boolean snapshotAutoRestore = false;
        /**
         * Consecutive no-delivery upstream failures before a route's circuit
         * opens. &lt;= 0 disables breaking entirely (allow everything).
         */
        private int circuitFailureThreshold = 5;
        /** How long an open circuit stays open before a half-open probe. */
        private int circuitCooldownMs = 30_000;

        public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
        public void setCircuitFailureThreshold(int v) { this.circuitFailureThreshold = v; }
        public int getCircuitCooldownMs() { return circuitCooldownMs; }
        public void setCircuitCooldownMs(int v) { this.circuitCooldownMs = v; }

        public int getDefaultQuotaTokens() { return defaultQuotaTokens; }
        public void setDefaultQuotaTokens(int v) { this.defaultQuotaTokens = v; }
        public int getRateLimitTokensPerSecond() { return rateLimitTokensPerSecond; }
        public void setRateLimitTokensPerSecond(int v) { this.rateLimitTokensPerSecond = v; }
        public int getRateLimitBurst() { return rateLimitBurst; }
        public void setRateLimitBurst(int v) { this.rateLimitBurst = v; }
        public int getCacheMaxSize() { return cacheMaxSize; }
        public void setCacheMaxSize(int v) { this.cacheMaxSize = v; }
        public int getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(int v) { this.cacheTtlSeconds = v; }
        public String getSnapshotPath() { return snapshotPath; }
        public void setSnapshotPath(String v) { this.snapshotPath = v; }
        public boolean isSnapshotAutoRestore() { return snapshotAutoRestore; }
        public void setSnapshotAutoRestore(boolean v) { this.snapshotAutoRestore = v; }
    }

    public static class Mock {
        private boolean enabled = true;
        private int port = 9090;
        private int delayMs = 20;
        private int chunks = 8;
        private int chunkIntervalMs = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public int getDelayMs() { return delayMs; }
        public void setDelayMs(int v) { this.delayMs = v; }
        public int getChunks() { return chunks; }
        public void setChunks(int v) { this.chunks = v; }
        public int getChunkIntervalMs() { return chunkIntervalMs; }
        public void setChunkIntervalMs(int v) { this.chunkIntervalMs = v; }
    }

    /**
     * Video task stickiness: a task lives on the upstream that created it, so
     * polling/content requests are pinned to the create route. The map is
     * bounded (max-entries + TTL) because tasks are finite-lived upstream jobs.
     */
    public static class Videos {
        private int stickyMaxEntries = 10_000;
        private int stickyTtlSeconds = 86_400;

        public int getStickyMaxEntries() { return stickyMaxEntries; }
        public void setStickyMaxEntries(int v) { this.stickyMaxEntries = v; }
        public int getStickyTtlSeconds() { return stickyTtlSeconds; }
        public void setStickyTtlSeconds(int v) { this.stickyTtlSeconds = v; }
    }

    /**
     * Bidirectional header pass-through whitelists (v13). Names are matched
     * case-insensitively; a trailing {@code *} matches any suffix
     * (e.g. {@code x-ratelimit-*}). Auth/transport headers are always stripped
     * regardless of these lists -- see {@code HeaderPolicy} implementations.
     */
    public static class Headers {
        private List<String> upstreamAllow = List.of(
                "anthropic-version", "openai-beta", "x-request-id", "traceparent", "tracestate", "baggage");
        private List<String> clientAllow = List.of(
                "x-request-id", "retry-after", "x-ratelimit-*", "anthropic-ratelimit-*",
                "anthropic-request-id", "anthropic-version", "openai-request-id");

        public List<String> getUpstreamAllow() { return upstreamAllow; }
        public void setUpstreamAllow(List<String> v) { this.upstreamAllow = v == null ? List.of() : v; }
        public List<String> getClientAllow() { return clientAllow; }
        public void setClientAllow(List<String> v) { this.clientAllow = v == null ? List.of() : v; }
    }

    /**
     * v13 observability surface. Trace context propagation rides the header
     * allowlists above (traceparent/tracestate/baggage reach the upstream);
     * full span export is opt-in via the {@code -Potel} Maven profile, which
     * puts the Micrometer Tracing bridge on the classpath and lets Boot's
     * auto-instrumentation emit spans. The request log capacity bounds the
     * in-memory ring behind {@code GET /admin/requests}.
     */
    public static class Observability {
        private int requestLogCapacity = 1000;

        public int getRequestLogCapacity() { return requestLogCapacity; }
        public void setRequestLogCapacity(int v) { this.requestLogCapacity = Math.max(1, v); }
    }

    /**
     * W4.1 guardrails: in-process deny patterns (regex vs the extracted prompt,
     * case-insensitive) and/or an external moderation engine (POST
     * {model,prompt} -> {"action":"ALLOW"|"DENY","reason"}). Both empty/off by
     * default; a configured HTTP engine fails closed when unavailable.
     */
    public static class Guardrails {
        private List<String> denyPatterns = new ArrayList<>();
        private String httpUrl = "";
        private int httpTimeoutMs = 800;

        public List<String> getDenyPatterns() { return denyPatterns; }
        public void setDenyPatterns(List<String> v) { this.denyPatterns = v == null ? List.of() : v; }
        public String getHttpUrl() { return httpUrl; }
        public void setHttpUrl(String v) { this.httpUrl = v == null ? "" : v; }
        public int getHttpTimeoutMs() { return httpTimeoutMs; }
        public void setHttpTimeoutMs(int v) { this.httpTimeoutMs = Math.max(50, v); }
    }

    /** B4: per-group hard budget entry. */
    public static class GroupBudget {
        private String group = "";
        private long budgetMicroUsd = 0;

        public String getGroup() { return group; }
        public void setGroup(String v) { this.group = v; }
        public long getBudgetMicroUsd() { return budgetMicroUsd; }
        public void setBudgetMicroUsd(long v) { this.budgetMicroUsd = v; }
    }

    /** B4: per-model token-bucket override; model supports exact/prefix/* matching. */
    public static class ModelLimit {
        private String model = "*";
        private long tokensPerSecond = 0;
        private long burst = 0;

        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public long getTokensPerSecond() { return tokensPerSecond; }
        public void setTokensPerSecond(long v) { this.tokensPerSecond = v; }
        public long getBurst() { return burst; }
        public void setBurst(long v) { this.burst = v; }
    }

    /**
     * W4.2 semantic cache keying: when {@code semantic-url} is set, cache keys
     * come from a quantized embedding of the prompt (bucket scale = similarity
     * granularity; higher = tighter match) instead of the exact body hash.
     * Embedder failure falls back to the exact key, never to an error.
     */
    public static class Cache {
        private String semanticUrl = "";
        private String semanticModel = "text-embedding";
        private int semanticTimeoutMs = 800;
        private double semanticBucketScale = 8.0;
        /** B2: cosine threshold for the similarity lookup (0..1). 0 = disabled
         *  (exact/bucketed keys only). */
        private double semanticThreshold = 0.0;
        /** B2: vector-store capacity (bounded linear scan). */
        private int semanticVectorCapacity = 5000;

        public String getSemanticUrl() { return semanticUrl; }
        public void setSemanticUrl(String v) { this.semanticUrl = v == null ? "" : v; }
        public String getSemanticModel() { return semanticModel; }
        public void setSemanticModel(String v) { this.semanticModel = v; }
        public int getSemanticTimeoutMs() { return semanticTimeoutMs; }
        public void setSemanticTimeoutMs(int v) { this.semanticTimeoutMs = Math.max(50, v); }
        public double getSemanticBucketScale() { return semanticBucketScale; }
        public void setSemanticBucketScale(double v) { this.semanticBucketScale = v; }
        public double getSemanticThreshold() { return semanticThreshold; }
        public void setSemanticThreshold(double v) { this.semanticThreshold = Math.max(0, Math.min(1, v)); }
        public int getSemanticVectorCapacity() { return semanticVectorCapacity; }
        public void setSemanticVectorCapacity(int v) { this.semanticVectorCapacity = Math.max(1, v); }
    }

    public static class RouteDef {
        private String id = "default";
        private String protocol = "";
        private String model = "*";
        private String targetModel = "";
        private String targetUrl = "";
        private String apiKey = "";
        /** v13 upstream dialect for auth: ""/"openai" = Bearer, "anthropic" = x-api-key. */
        private String provider = "";
        private int weight = 1;
        private List<String> fallbacks = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        /** "" or "*" = any protocol; else chat|responses|messages|images|videos. */
        public String getProtocol() { return protocol; }
        public void setProtocol(String v) { this.protocol = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getTargetModel() { return targetModel; }
        public void setTargetModel(String v) { this.targetModel = v; }
        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String v) { this.targetUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public int getWeight() { return weight; }
        public void setWeight(int v) { this.weight = v; }
        public List<String> getFallbacks() { return fallbacks; }
        public void setFallbacks(List<String> v) { this.fallbacks = v; }
    }

    public static class ApiKeyDef {
        private String id = "demo";
        private String token = "sk-demo";
        private String name = "demo";
        private long quotaTokens = 1_000_000;
        private List<String> allowedModels = new ArrayList<>();
        /** v13 ownership label for aggregated spend views; "" = ungrouped. */
        private String group = "";
        /** v13 hard $ budget (micro-USD, cumulative). 0 = unmetered. */
        private long budgetMicroUsd = 0;
        /** B7: free-form key metadata (tags, owner, environment...). */
        private Map<String, String> metadata = new LinkedHashMap<>();

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getToken() { return token; }
        public void setToken(String v) { this.token = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public long getQuotaTokens() { return quotaTokens; }
        public void setQuotaTokens(long v) { this.quotaTokens = v; }
        public List<String> getAllowedModels() { return allowedModels; }
        public void setAllowedModels(List<String> v) { this.allowedModels = v; }
        public String getGroup() { return group; }
        public void setGroup(String v) { this.group = v; }
        public long getBudgetMicroUsd() { return budgetMicroUsd; }
        public void setBudgetMicroUsd(long v) { this.budgetMicroUsd = v; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> v) { this.metadata = v == null ? Map.of() : v; }
    }

    /**
     * v13 model catalog entry. Prices are micro-USD per 1M tokens
     * ({@code $1.20/M} = {@code 1200000}); a model named {@code *} is the
     * fallback for unknown models.
     */
    public static class ModelDef {
        private String model = "";
        private String provider = "";
        private long contextWindowTokens = 0;
        private long inputPriceMicroUsdPerMillion = 0;
        private long outputPriceMicroUsdPerMillion = 0;

        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public long getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(long v) { this.contextWindowTokens = v; }
        public long getInputPriceMicroUsdPerMillion() { return inputPriceMicroUsdPerMillion; }
        public void setInputPriceMicroUsdPerMillion(long v) { this.inputPriceMicroUsdPerMillion = v; }
        public long getOutputPriceMicroUsdPerMillion() { return outputPriceMicroUsdPerMillion; }
        public void setOutputPriceMicroUsdPerMillion(long v) { this.outputPriceMicroUsdPerMillion = v; }
    }
}
