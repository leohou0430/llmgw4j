package io.github.leohou0430.llmgw.governance.inmemory;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.Embedder;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI-compatible /embeddings adapter (W4.2): POST {model,input} ->
 * data[0].embedding. Determinism is the EMBEDDER'S job (the bundled mock
 * upstream serves a deterministic bag-of-words vector); on any failure this
 * adapter returns an empty vector and the cache key falls back to the exact
 * body hash -- a broken embedder degrades to exact caching, never to errors.
 */
public class HttpEmbedder implements Embedder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbedder.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String url;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    /**
     * A1（性能打磨）：同 prompt 的 embedding 结果缓存。lookupCache 与 cache 写入
     * 会各调一次 embed；语义缓存的本质又是「相似问复用」，因此向量级缓存把
     * 重复网络调用降为 0。TTL 跟随语义缓存 TTL 配置。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, double[]> vectorCache;

    public HttpEmbedder(GatewayProperties properties) {
        this.url = properties.getCache().getSemanticUrl();
        this.apiKey = properties.getUpstream().getApiKey();
        this.model = properties.getCache().getSemanticModel();
        this.timeoutMs = Math.max(50, properties.getCache().getSemanticTimeoutMs());
        this.vectorCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofSeconds(
                        Math.max(1, properties.getGovernance().getCacheTtlSeconds())))
                .build();
    }

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    @Override
    public double[] embed(String text) {
        if (!enabled() || text == null || text.isBlank()) {
            return new double[0];
        }
        double[] cached = vectorCache.getIfPresent(text);
        if (cached != null) {
            return cached;
        }
        double[] vector = embedRemote(text);
        if (vector.length > 0) {
            vectorCache.put(text, vector);
        }
        return vector;
    }

    private double[] embedRemote(String text) {
        try {
            String body = ProtocolCodec.toJson(java.util.Map.of(
                    "model", model == null || model.isBlank() ? "embedding" : model,
                    "input", text));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${")) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Embeddings endpoint {} answered {}, falling back to exact key",
                        url, response.statusCode());
                return new double[0];
            }
            var data = ProtocolCodec.tryParse(response.body()).path("data");
            if (data.isArray() && !data.isEmpty()) {
                var embedding = data.get(0).path("embedding");
                if (embedding.isArray()) {
                    double[] out = new double[embedding.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = embedding.get(i).asDouble();
                    }
                    return out;
                }
            }
            return new double[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new double[0];
        } catch (Exception e) {
            log.warn("Embeddings endpoint {} unreachable ({}), falling back to exact key",
                    url, e.toString());
            return new double[0];
        }
    }
    @Override public void close() { client.close(); }
}
