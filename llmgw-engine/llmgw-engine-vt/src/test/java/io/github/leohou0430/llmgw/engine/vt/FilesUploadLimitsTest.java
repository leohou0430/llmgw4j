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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.7 seam: a declared Content-Length over the cap is rejected with 413 before
 * the body is read or a permit is taken; an unknown length is rejected
 * cumulatively by the scanner; and parallel uploads draw from the same total
 * concurrency pool (a saturated pool 503s, and every terminal path returns its
 * permit exactly once).
 */
class FilesUploadLimitsTest {

    private static final String B = "----llmgwBoundary7ma4d9";

    private static byte[] multipart(byte[] fileContent) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + B + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes("assistants".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("\r\n--" + B + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(fileContent);
        out.writeBytes(("\r\n--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @Test
    void knownLengthOverCapIs413WithoutReadingBodyOrTakingPermit() throws Exception {
        byte[] body = multipart(new byte[10]);
        var h = new Harness(1000, 1, false);
        var resp = h.response();
        h.controller.handleFileUpload(h.request(body, "multipart/form-data; boundary=" + B, 1001), resp.proxy);
        assertEquals(413, resp.status[0]);
        assertFalse(h.bodyRead.get(), "a declared over-cap length must be rejected before the body is read");
        assertEquals(0, h.provider.rawCalls.get());
        // The permit was never taken: a normal request still succeeds on a pool of ONE.
        var ok = h.response();
        h.controller.handleFileUpload(h.request(multipart(new byte[10]), "multipart/form-data; boundary=" + B, -1), ok.proxy);
        assertEquals(200, ok.status[0]);
        h.controller.close();
    }

    @Test
    void unknownLengthOverCapIs413Cumulatively() throws Exception {
        byte[] body = multipart(new byte[40000]);
        var h = new Harness(100, 4, false); // whole-multipart bound = 100 bytes
        var resp = h.response();
        h.controller.handleFileUpload(h.request(body, "multipart/form-data; boundary=" + B, -1), resp.proxy);
        assertEquals(413, resp.status[0]);
        assertTrue(h.bodyRead.get(), "unknown length must be scanned cumulatively, not pre-rejected");
        assertEquals(0, h.provider.rawCalls.get(), "an over-limit body must never reach the upstream");
        h.controller.close();
    }

    @Test
    void fourParallelUploadsShareTotalConcurrencyAndReturnPermits() throws Exception {
        var h = new Harness(4 << 20, 4, true);
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        h.provider.gate = release;
        h.provider.started = started;
        var errors = new ArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        var statuses = new AtomicInteger[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            statuses[i] = new AtomicInteger();
            var t = new Thread(() -> {
                try {
                    var resp = h.response();
                    h.controller.handleFileUpload(
                            h.request(multipart(new byte[10]), "multipart/form-data; boundary=" + B, -1), resp.proxy);
                    statuses[idx].set(resp.status[0]);
                } catch (Throwable e) {
                    synchronized (errors) { errors.add(e); }
                }
            });
            threads.add(t);
            t.start();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "all four uploads must reach the upstream");
        // Pool of FOUR is saturated: a fifth upload is rejected with 503.
        var denied = h.response();
        h.controller.handleFileUpload(h.request(multipart(new byte[10]), "multipart/form-data; boundary=" + B, -1), denied.proxy);
        assertEquals(503, denied.status[0]);
        release.countDown();
        for (var t : threads) {
            t.join(5000);
        }
        assertTrue(errors.isEmpty(), "upload threads failed: " + errors);
        for (var s : statuses) {
            assertEquals(200, s.get());
        }
        // Every permit returned: a fresh upload succeeds again.
        var ok = h.response();
        h.controller.handleFileUpload(h.request(multipart(new byte[10]), "multipart/form-data; boundary=" + B, -1), ok.proxy);
        assertEquals(200, ok.status[0], "permits must return to zero after parallel uploads complete");
        h.controller.close();
    }

    static final class LatchedProvider implements UpstreamProvider {
        final AtomicInteger rawCalls = new AtomicInteger();
        volatile CountDownLatch gate;
        volatile CountDownLatch started;

        @Override public String id() { return "latched"; }
        @Override public Flow.Publisher<String> streamRaw(UpstreamRequest r, HeaderListener l) { throw new UnsupportedOperationException(); }
        @Override public void streamRawBlocking(UpstreamRequest r, ChunkSink s) { throw new UnsupportedOperationException(); }
        @Override public String completeRaw(UpstreamRequest r, HeaderListener l) { throw new UnsupportedOperationException(); }
        @Override public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest r) { throw new UnsupportedOperationException(); }

        @Override public byte[] fetchRaw(UpstreamRequest req) {
            rawCalls.incrementAndGet();
            drain(req.body());
            if (started != null) {
                started.countDown();
            }
            if (gate != null) {
                try {
                    assertTrue(gate.await(5, TimeUnit.SECONDS), "gate released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return "{\"id\":\"f\",\"object\":\"file\"}".getBytes(StandardCharsets.UTF_8);
        }

        private static void drain(ByteStream body) {
            CountDownLatch done = new CountDownLatch(1);
            body.subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer b) { }
                @Override public void onError(Throwable t) { done.countDown(); }
                @Override public void onComplete() { done.countDown(); }
            });
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS), "body drained");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    static final class Resp {
        final int[] status = {0};
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        volatile boolean committed;
        final HttpServletResponse proxy;

        Resp() {
            var out = new ServletOutputStream() {
                public void write(int b) { committed = true; body.write(b); }
                @Override public void write(byte[] b, int off, int len) { committed = true; body.write(b, off, len); }
                public boolean isReady() { return true; }
                public void setWriteListener(WriteListener l) {}
            };
            proxy = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> switch (m.getName()) {
                        case "setStatus" -> { status[0] = (Integer) a[0]; yield null; }
                        case "getStatus" -> status[0];
                        case "setContentType" -> null;
                        case "getOutputStream" -> out;
                        case "isCommitted" -> committed;
                        default -> defaultValue(m.getReturnType());
                    });
        }
    }

    static final class Harness {
        final VirtualThreadGatewayController controller;
        final LatchedProvider provider = new LatchedProvider();
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final AtomicBoolean bodyRead = new AtomicBoolean();

        Harness(long maxRequestBytes, int maxConcurrent, boolean latched) {
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
            var properties = new GatewayProperties();
            properties.setMaxConcurrentRequests(maxConcurrent);
            properties.setMaxRequestBytes(maxRequestBytes);
            controller = new VirtualThreadGatewayController(chain, router, provider,
                    CircuitBreaker.noop(), properties, headers, tasks);
        }

        Resp response() { return new Resp(); }

        HttpServletRequest request(byte[] body, String contentType, long contentLength) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer test-token");
            headers.put("Content-Type", contentType);
            var in = new ServletInputStream() {
                final ByteArrayInputStream src = new ByteArrayInputStream(body);
                public int read() { bodyRead.set(true); return src.read(); }
                @Override public int read(byte[] b, int off, int len) { bodyRead.set(true); return src.read(b, off, len); }
                public boolean isFinished() { return src.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener l) {}
            };
            return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> switch (m.getName()) {
                        case "getInputStream" -> in;
                        case "getContentType" -> contentType;
                        case "getContentLengthLong" -> contentLength;
                        case "getContentLength" -> (int) contentLength;
                        case "getHeader" -> headers.get((String) a[0]);
                        case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                        default -> defaultValue(m.getReturnType());
                    });
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