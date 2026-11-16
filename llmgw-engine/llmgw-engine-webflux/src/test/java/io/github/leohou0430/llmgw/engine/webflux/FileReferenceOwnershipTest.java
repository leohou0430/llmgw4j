package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.*;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1.9 需求 c：跨项目 file 引用 → 统一 404 的引擎级端到端回归（WebFlux 数据面）。
 * 驱动真实控制器分发路径（{@code WebfluxGatewayController.dispatch} 的归属分支），
 * 而非仅 {@code EngineRoutes.ownsReferencedFiles} 静态方法；使用真实
 * {@link InMemoryTaskRouteSticky} 作为归属存储。
 */
class FileReferenceOwnershipTest {

    /** 携带 file_id 的 chat 请求；归属校验先于入口 codec 解码，故 file 部件不会先被 codec 拒绝。 */
    static final String FILE_BODY =
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"file\",\"file\":{\"file_id\":\"f\"}}]}]}";
    static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"completion_tokens\":1}}";

    @Test
    void crossTenantFileReferenceIsUnified404BeforeUpstream() {
        try (var fixture = new Fixture()) {
            // file "f" 归属另一租户 "other"；本请求 chain keyId 为 "tenant" → 归属不通过。
            fixture.tasks.bind("other", "files", "f", "test");
            var response = fixture.request("/v1/chat/completions", FILE_BODY);
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertEquals(ProtocolCodec.errorJson("referenced file not found on this route"),
                    response.getBodyAsString().block());
            assertEquals(0, fixture.provider.calls.get(), "ownership rejection must precede any upstream call");
        }
    }

    @Test
    void sameTenantFileReferenceProceedsToUpstream() {
        try (var fixture = new Fixture()) {
            // file "f" 归属本租户 "tenant" 且路由 id 匹配 → 归属通过 → 正常 200。
            fixture.tasks.bind("tenant", "files", "f", "test");
            var response = fixture.request("/v1/chat/completions", FILE_BODY);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(1, fixture.provider.calls.get());
        }
    }

    static class OkProvider implements UpstreamProvider {
        final AtomicInteger calls = new AtomicInteger();
        public String id() { return "ok"; }
        public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) { throw new UnsupportedOperationException(); }
        public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) { throw new UnsupportedOperationException(); }
        public String completeRaw(UpstreamRequest request, HeaderListener listener) { calls.incrementAndGet(); return OK; }
    }

    @Configuration(proxyBeanMethods = false)
    static class WebConfig extends WebFluxConfigurationSupport {
    }

    static class Fixture implements AutoCloseable {
        final OkProvider provider = new OkProvider();
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final HttpHandler handler;

        Fixture() {
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(10);
            var route = new Route("test", "*", "", "http://unused", "", 1, List.of());
            var router = new Router() {
                public Route resolve(String model) { return route; }
                public Route resolve(ProtocolKind kind, String model) { return route; }
                public Route byId(String id) { return route; }
                public List<Route> fallbacks(Route primary) { return List.of(); }
                public List<Route> all() { return List.of(route); }
            };
            var chain = new GovernanceChain() {
                public GatewayDecision enter(String token, ParsedRequest request, Route target) { return GatewayDecision.allow("tenant", request, target); }
                public Optional<String> lookupCache(String key, ParsedRequest request) { return Optional.empty(); }
                public void cache(String key, ParsedRequest request, String body) {}
                public void finish(GatewayContext context, boolean success) {}
                public Map<String, Object> snapshot() { return Map.of(); }
            };
            var headers = new HeaderPolicy() {
                public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) { return Map.of(); }
                public List<String> toClient(Map<String, List<String>> values) { return List.of(); }
            };
            context.getEnvironment().setActiveProfiles("webflux");
            context.registerBean(GatewayProperties.class, () -> properties);
            context.register(WebConfig.class, WebfluxEngineConfiguration.class);
            context.registerBean(WebfluxGatewayController.class,
                    () -> new WebfluxGatewayController(chain, router, provider, CircuitBreaker.noop(), properties, headers, tasks));
            context.refresh();
            handler = WebHttpHandlerBuilder.applicationContext(context).build();
        }

        MockServerHttpResponse request(String path, String body) {
            var response = new MockServerHttpResponse();
            handler.handle(MockServerHttpRequest.post(path).contentType(MediaType.APPLICATION_JSON).body(body), response)
                    .block(Duration.ofSeconds(2));
            return response;
        }

        public void close() { context.close(); }
    }
}