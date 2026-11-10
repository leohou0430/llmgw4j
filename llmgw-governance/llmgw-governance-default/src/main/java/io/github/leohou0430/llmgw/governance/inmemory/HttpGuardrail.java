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
 * An unreachable or malformed configured engine is unavailable, never approval.
 * The endpoint URL is the only config; the JSON contract mirrors what the
 * bundled mock upstream serves at /mock/v1/guardrail for smoke tests.
 */
public class HttpGuardrail implements Guardrail, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpGuardrail.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String url;
    private final int timeoutMs;
    /**
     * A2（性能打磨）：同 prompt 的审核决策短窗缓存（60s / 1000 条）。外部引擎
     * 调用是同步 HTTP；只缓存有效决策，不缓存依赖故障。
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
        String cacheKey = ProtocolCodec.toJson(java.util.List.of(request.kind().wireName(),
                request.model() == null ? "" : request.model(), text));
        Verdict cached = decisionCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        Verdict verdict = inspectRemote(request, text);
        if (verdict.action() != Action.UNAVAILABLE) {
            decisionCache.put(cacheKey, verdict);
        }
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
                log.warn("Guardrail engine {} answered {}", url, response.statusCode());
                return Verdict.unavailable("guardrail service unavailable");
            }
            var root = ProtocolCodec.tryParse(response.body());
            String action = root.path("action").asText("");
            if ("DENY".equalsIgnoreCase(action)) {
                return Verdict.deny("request blocked by guardrail " + name()
                        + ": " + root.path("reason").asText(""));
            }
            return "ALLOW".equalsIgnoreCase(action) ? Verdict.allow()
                    : Verdict.unavailable("invalid guardrail response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.unavailable("guardrail interrupted");
        } catch (Exception e) {
            log.warn("Guardrail engine {} unreachable ({})", url, e.toString());
            return Verdict.unavailable("guardrail service unavailable");
        }
    }
    @Override public void close() { client.close(); }
}
