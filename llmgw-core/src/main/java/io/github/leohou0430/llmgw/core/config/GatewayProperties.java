package io.github.leohou0430.llmgw.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Upstream upstream = new Upstream();
    private final Governance governance = new Governance();
    private final Mock mock = new Mock();
    private final Videos videos = new Videos();
    private List<RouteDef> routes = new ArrayList<>();
    private List<ApiKeyDef> apiKeys = new ArrayList<>();
    private boolean governanceEnabled = true;
    /** Hard cap for a request body, protects both data planes from OOM. */
    private long maxRequestBytes = 4 * 1024 * 1024;
    /** Empty = admin endpoints open (local demo). Set it in any shared environment. */
    private String adminToken = "";

    public Upstream getUpstream() { return upstream; }
    public Governance getGovernance() { return governance; }
    public Mock getMock() { return mock; }
    public Videos getVideos() { return videos; }
    public List<RouteDef> getRoutes() { return routes; }
    public void setRoutes(List<RouteDef> routes) { this.routes = routes; }
    public List<ApiKeyDef> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<ApiKeyDef> apiKeys) { this.apiKeys = apiKeys; }
    public boolean isGovernanceEnabled() { return governanceEnabled; }
    public void setGovernanceEnabled(boolean governanceEnabled) { this.governanceEnabled = governanceEnabled; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long v) { this.maxRequestBytes = v; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String v) { this.adminToken = v; }

    public static class Upstream {
        /** mock | openai-compatible */
        private String type = "mock";
        private String baseUrl = "http://127.0.0.1:9090/mock/v1";
        private String apiKey = "${DASHSCOPE_API_KEY:}";
        private int timeoutMs = 60000;
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

    public static class RouteDef {
        private String id = "default";
        private String protocol = "";
        private String model = "*";
        private String targetModel = "";
        private String targetUrl = "";
        private String apiKey = "";
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
    }
}
