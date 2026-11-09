package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.Guardrail;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * External moderation engine adapter (W4.1): POSTs {model, prompt} to the
 * configured URL and expects {"action":"ALLOW"|"DENY","reason":"..."} back.
 * Fail-open by design: an unreachable or malformed engine must not take the
 * whole gateway down -- the denial is the ENGINE's decision, not an outage.
 * The endpoint URL is the only config; the JSON contract mirrors what the
 * bundled mock upstream serves at /mock/v1/guardrail for smoke tests.
 */
public class HttpGuardrail implements Guardrail {

    private static final Logger log = LoggerFactory.getLogger(HttpGuardrail.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String url;
    private final int timeoutMs;
    /**
     * A2（性能打磨）：同 prompt 的审核决策短窗缓存（60s / 1000 条）。外部引擎
     * 调用是同步 HTTP；缓存在重复/回放流量下把网络开销降为 0，fail-open 语义
     * 不受影响（缓存只存已完成的决策）。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Verdict> decisionCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofSeconds(60))
                    .build();

    public HttpGuardrail(GatewayProperties properties) {
        this.url = properties.getGuardrails().getHttpUrl();
        this.timeoutMs = Math.max(50, properties.getGuardrails().getHttpTimeoutMs());
    }

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    @Override
    public String name() {
        return "http-engine";
    }

    @Override
    public Verdict inspectRequest(ParsedRequest request) {
        if (!enabled()) {
            return Verdict.allow();
        }
        String text = request.promptText() == null ? "" : request.promptText();
        Verdict cached = decisionCache.getIfPresent(text);
        if (cached != null) {
            return cached;
        }
        Verdict verdict = inspectRemote(request, text);
        decisionCache.put(text, verdict);
        return verdict;
    }

    private Verdict inspectRemote(ParsedRequest request, String text) {
        try {
            String body = ProtocolCodec.toJson(java.util.Map.of(
                    "model", request.model() == null ? "" : request.model(),
                    "prompt", request.promptText() == null ? "" : request.promptText()));
            HttpRequest http = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Guardrail engine {} answered {}, failing open", url, response.statusCode());
                return Verdict.allow();
            }
            var root = ProtocolCodec.tryParse(response.body());
            String action = root.path("action").asText("ALLOW");
            if ("DENY".equalsIgnoreCase(action)) {
                return Verdict.deny("request blocked by guardrail " + name()
                        + ": " + root.path("reason").asText(""));
            }
            return Verdict.allow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.allow();
        } catch (Exception e) {
            log.warn("Guardrail engine {} unreachable ({}), failing open", url, e.toString());
            return Verdict.allow();
        }
    }
}
