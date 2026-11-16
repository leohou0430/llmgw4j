package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1.9 需求 c：跨项目 file 引用 → 统一 404 的引擎级端到端回归（VT 数据面）。
 * 驱动真实控制器分发路径（{@code handleChat → handleKind → handleKindWithinCapacity}
 * 的归属分支），而非仅静态方法；使用真实 {@link InMemoryTaskRouteSticky}。
 * engine-vt 无 spring-test 依赖，故沿用 StreamDisconnectTest 的 Proxy 桩件手法。
 */
class FileReferenceOwnershipTest {

    static final String FILE_BODY =
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"file\",\"file\":{\"file_id\":\"f\"}}]}]}";
    static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"completion_tokens\":1}}";

    @Test
    void crossTenantFileReferenceIsUnified404BeforeUpstream() throws Exception {
        var harness = new Harness(FILE_BODY);
        // file "f" 归属另一租户 "other"；本请求 chain keyId 为 "tenant" → 归属不通过。
        harness.tasks.bind("other", "files", "f", "test");
        harness.dispatch();
        assertEquals(404, harness.status[0]);
        assertTrue(harness.body.toString(StandardCharsets.UTF_8).contains("referenced file not found on this route"),
                harness.body.toString(StandardCharsets.UTF_8));
        assertEquals(0, harness.providerCalls.get(), "ownership rejection must precede any upstream call");
    }

    @Test
    void sameTenantFileReferenceProceedsToUpstream() throws Exception {
        var harness = new Harness(FILE_BODY);
        // file "f" 归属本租户 "tenant" 且路由 id 匹配 → 归属通过 → 正常 200。
        harness.tasks.bind("tenant", "files", "f", "test");
        harness.dispatch();
        assertEquals(200, harness.status[0]);
        assertEquals(1, harness.providerCalls.get());
    }

    /** Proxy 桩件 + 真实控制器：请求体/响应状态与字节均可观测。 */
    static final class Harness {
        final int[] status = {0};
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicInteger providerCalls = new AtomicInteger();
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final VirtualThreadGatewayController controller;
        final HttpServletRequest request;
        final HttpServletResponse response;

        Harness(String json) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer test-token");
            headers.put("Content-Type", "application/json");

            var servletIn = new ServletInputStream() {
                final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                public int read() { return in.read(); }
                public boolean isFinished() { return in.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) {}
            };
            request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getInputStream" -> servletIn;
                        case "getHeader" -> headers.get((String) args[0]);
                        case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                        default -> defaultValue(method.getReturnType());
                    });

            var servletOut = new ServletOutputStream() {
                public void write(int b) { body.write(b); }
                public boolean isReady() { return true; }
                public void setWriteListener(WriteListener listener) {}
            };
            response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> { status[0] = (Integer) args[0]; yield null; }
                        case "getStatus" -> status[0];
                        case "getOutputStream" -> servletOut;
                        case "isCommitted" -> false;
                        default -> defaultValue(method.getReturnType());
                    });

            var route = new Route("test", "*", "", "http://unused", "", 1, List.of());
            var router = new Router() {
                public Route resolve(String model) { return route; }
                public Route resolve(ProtocolKind kind, String model) { return route; }
                public Route byId(String id) { return route; }
                public List<Route> fallbacks(Route primary) { return List.of(); }
                public List<Route> all() { return List.of(route); }
            };
            var chain = new GovernanceChain() {
                public GatewayDecision enter(String token, ParsedRequest req, Route target) { return GatewayDecision.allow("tenant", req, target); }
                public Optional<String> lookupCache(String key, ParsedRequest req) { return Optional.empty(); }
                public void cache(String key, ParsedRequest req, String responseBody) {}
                public void finish(GatewayContext context, boolean success) {}
                public Map<String, Object> snapshot() { return Map.of(); }
            };
            var headerPolicy = new HeaderPolicy() {
                public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) { return Map.of(); }
                public List<String> toClient(Map<String, List<String>> values) { return List.of(); }
            };
            var provider = new UpstreamProvider() {
                public String id() { return "ok"; }
                public Flow.Publisher<String> streamRaw(UpstreamRequest req, HeaderListener listener) { throw new UnsupportedOperationException(); }
                public void streamRawBlocking(UpstreamRequest req, ChunkSink sink) { throw new UnsupportedOperationException(); }
                public String completeRaw(UpstreamRequest req, HeaderListener listener) { providerCalls.incrementAndGet(); return OK; }
            };
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(10);
            controller = new VirtualThreadGatewayController(chain, router, provider,
                    CircuitBreaker.noop(), properties, headerPolicy, tasks);
        }

        void dispatch() throws IOException {
            controller.handleChat(request, response);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}