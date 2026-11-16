package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.*;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.3 seam: Files binary content download must adapt {@code fetchStream} to a
 * bounded {@code Flux<DataBuffer>} written off the event loop (never one whole
 * byte[]), release the upstream source on completion, and surface a pre-commit
 * upstream failure as a real 502. JSON metadata GET must keep using fetchRaw.
 * Drives the real controller through the reactive HttpHandler seam.
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
    void streamsFileContentInBoundedChunksAndReleasesSource() {
        byte[] payload = pattern(100000);
        AtomicInteger closes = new AtomicInteger();
        ByteStream.Source source = new ByteStream.Source() {
            private int off;
            @Override public ByteChunk next() {
                if (off >= payload.length) {
                    return null;
                }
                int n = Math.min(16 * 1024, payload.length - off);
                ByteBuffer b = ByteBuffer.wrap(payload, off, n);
                off += n;
                return new ByteChunk(b);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        try (var fixture = new Fixture(ByteStream.of(source))) {
            fixture.tasks.bind("tenant", "files", "f", "test");
            var response = fixture.get("/v1/files/f/content");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
            List<DataBuffer> buffers = response.getBody().collectList().block(Duration.ofSeconds(5));
            assertNotNull(buffers);
            assertTrue(buffers.size() >= 2, "body must arrive as multiple bounded DataBuffers");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (DataBuffer b : buffers) {
                byte[] a = new byte[b.readableByteCount()];
                b.read(a);
                out.writeBytes(a);
                DataBufferUtils.release(b);
            }
            assertArrayEquals(payload, out.toByteArray());
            assertEquals(1, fixture.provider.streamCalls.get(), "content download must use fetchStream");
            assertEquals(0, fixture.provider.rawCalls.get(), "content download must not buffer via fetchRaw");
            assertEquals(1, closes.get(), "completion releases the upstream source exactly once");
        }
    }

    @Test
    void upstreamErrorBeforeFirstByteIs502() {
        ByteStream.Source failing = new ByteStream.Source() {
            @Override public ByteChunk next() throws java.io.IOException { throw new java.io.IOException("upstream gone"); }
            @Override public void close() { }
        };
        try (var fixture = new Fixture(ByteStream.of(failing))) {
            fixture.tasks.bind("tenant", "files", "f", "test");
            var response = fixture.get("/v1/files/f/content");
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
            assertEquals(1, fixture.provider.streamCalls.get());
            assertTrue(response.getBodyAsString().block(Duration.ofSeconds(5)).contains("error"));
        }
    }

    @Test
    void jsonMetadataGetStillUsesFetchRaw() {
        try (var fixture = new Fixture(ByteStream.ofByteArray(new byte[0]))) {
            fixture.tasks.bind("tenant", "files", "f", "test");
            var response = fixture.get("/v1/files/f");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
            assertEquals(1, fixture.provider.rawCalls.get(), "JSON metadata must keep the fetchRaw path");
            assertEquals(0, fixture.provider.streamCalls.get());
        }
    }

    static class StreamProvider implements UpstreamProvider {
        final AtomicInteger streamCalls = new AtomicInteger();
        final AtomicInteger rawCalls = new AtomicInteger();
        final ByteStream download;
        StreamProvider(ByteStream download) { this.download = download; }
        public String id() { return "stream"; }
        public Flow.Publisher<String> streamRaw(UpstreamRequest r, HeaderListener l) { throw new UnsupportedOperationException(); }
        public void streamRawBlocking(UpstreamRequest r, ChunkSink s) { throw new UnsupportedOperationException(); }
        public String completeRaw(UpstreamRequest r, HeaderListener l) { throw new UnsupportedOperationException(); }
        @Override public byte[] fetchRaw(UpstreamRequest r) {
            rawCalls.incrementAndGet();
            return "{\"id\":\"f\",\"object\":\"file\"}".getBytes(StandardCharsets.UTF_8);
        }
        @Override public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest r) {
            streamCalls.incrementAndGet();
            return download;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WebConfig extends WebFluxConfigurationSupport {
    }

    static class Fixture implements AutoCloseable {
        final StreamProvider provider;
        final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final HttpHandler handler;

        Fixture(ByteStream download) {
            provider = new StreamProvider(download);
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

        MockServerHttpResponse get(String path) {
            var response = new MockServerHttpResponse();
            handler.handle(MockServerHttpRequest.get(path).header("Authorization", "Bearer test-token").build(), response)
                    .block(Duration.ofSeconds(5));
            return response;
        }

        public void close() { context.close(); }
    }
}