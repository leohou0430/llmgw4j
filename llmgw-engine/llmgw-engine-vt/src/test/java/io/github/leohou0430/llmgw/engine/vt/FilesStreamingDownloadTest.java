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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.4 seam: Files binary content download must drain {@code fetchStream} in
 * bounded chunks straight to the servlet output (never a whole byte[]), and an
 * upstream failure before the first byte must surface as a real 502. Drives the
 * real controller dispatch ({@code handleFileContent → handleStickyFetch}) with
 * Proxy servlet stubs, mirroring FileReferenceOwnershipTest.
 */
class FilesStreamingDownloadTest {

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    @Test
    void streamsFileContentInBoundedChunks() throws Exception {
        byte[] payload = pattern(100000);
        var h = new Harness(ByteStream.ofByteArray(payload));
        h.tasks.bind("tenant", "files", "f", "test");
        h.controller.handleFileContent("f", h.request, h.response);
        assertEquals(200, h.status[0]);
        assertEquals("application/octet-stream", h.contentType[0]);
        assertArrayEquals(payload, h.body.toByteArray());
        assertEquals(1, h.streamCalls.get(), "content download must use fetchStream");
        assertEquals(0, h.rawCalls.get(), "content download must not buffer via fetchRaw");
        assertTrue(h.chunkWrites.get() >= 2, "body must arrive in multiple bounded writes");
    }

    @Test
    void upstreamErrorBeforeFirstByteIs502() throws Exception {
        ByteStream.Source failing = new ByteStream.Source() {
            @Override public ByteChunk next() throws IOException { throw new IOException("upstream gone"); }
            @Override public void close() { }
        };
        var h = new Harness(ByteStream.of(failing));
        h.tasks.bind("tenant", "files", "f", "test");
        h.controller.handleFileContent("f", h.request, h.response);
        assertEquals(502, h.status[0]);
        assertEquals(1, h.streamCalls.get());
        assertTrue(h.body.toString(StandardCharsets.UTF_8).contains("error"),
                "pre-commit failure renders a real 502 error envelope, not an empty 200");
    }

    @Test
    void clientDisconnectCancelsUpstreamAndClosesSourceOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ByteStream.Source source = new ByteStream.Source() {
            private int n;
            @Override public ByteChunk next() {
                if (n++ >= 5) {
                    return null;
                }
                return new ByteChunk(ByteBuffer.wrap(pattern(16)));
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var h = new Harness(ByteStream.of(source), true);
        h.tasks.bind("tenant", "files", "f", "test");
        h.controller.handleFileContent("f", h.request, h.response);
        assertEquals(1, h.streamCalls.get());
        assertEquals(1, closes.get(), "client disconnect must release the upstream source exactly once");
    }

    static final class Harness {
        final int[] status = {0};
        final String[] contentType = {null};
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicInteger chunkWrites = new AtomicInteger();
        final AtomicInteger streamCalls = new AtomicInteger();
        final AtomicInteger rawCalls = new AtomicInteger();
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final VirtualThreadGatewayController controller;
        final HttpServletRequest request;
        final HttpServletResponse response;
        final boolean disconnect;
        volatile boolean committed;

        Harness(ByteStream download) {
            this(download, false);
        }

        Harness(ByteStream download, boolean disconnect) {
            this.disconnect = disconnect;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer test-token");
            var servletIn = new ServletInputStream() {
                final ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
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
                public void write(int b) { committed = true; body.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (disconnect && chunkWrites.get() >= 1) {
                        throw new IOException("client gone");
                    }
                    committed = true;
                    chunkWrites.incrementAndGet();
                    body.write(b, off, len);
                }
                public boolean isReady() { return true; }
                public void setWriteListener(WriteListener listener) {}
            };
            response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> { status[0] = (Integer) args[0]; yield null; }
                        case "getStatus" -> status[0];
                        case "setContentType" -> { contentType[0] = (String) args[0]; yield null; }
                        case "getOutputStream" -> servletOut;
                        case "isCommitted" -> committed;
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
                public String id() { return "stream"; }
                public Flow.Publisher<String> streamRaw(UpstreamRequest req, HeaderListener listener) { throw new UnsupportedOperationException(); }
                public void streamRawBlocking(UpstreamRequest req, ChunkSink sink) { throw new UnsupportedOperationException(); }
                public String completeRaw(UpstreamRequest req, HeaderListener listener) { throw new UnsupportedOperationException(); }
                @Override public byte[] fetchRaw(UpstreamRequest req) { rawCalls.incrementAndGet(); throw new UnsupportedOperationException("content must stream"); }
                @Override public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest req) { streamCalls.incrementAndGet(); return download; }
            };
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(10);
            controller = new VirtualThreadGatewayController(chain, router, provider,
                    CircuitBreaker.noop(), properties, headerPolicy, tasks);
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