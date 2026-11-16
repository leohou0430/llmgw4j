package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockingHeartbeatTest {
    @Test void blockedHeartbeatMustNotStarveAnotherRequestsBodyDeadline() throws Exception {
        var heartbeatEntered = new CountDownLatch(1);
        var releaseSink = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write(':'); out.flush();
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            var properties = new GatewayProperties();
            properties.getUpstream().setHeartbeatIntervalMs(25);
            try (var client = new JdkHttpUpstream(properties)) {
                var streaming = workers.submit(() -> client.streamRawBlocking(request(server, 4000), line -> {
                    if (line.startsWith(":")) {
                        heartbeatEntered.countDown();
                        try { releaseSink.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { throw new IllegalStateException(e); }
                    }
                }));
                try {
                    assertTrue(heartbeatEntered.await(2, TimeUnit.SECONDS));
                    var second = workers.submit(() -> client.completeRaw(request(server, 250)));
                    var error = assertThrows(ExecutionException.class, () -> second.get(1, TimeUnit.SECONDS));
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    assertInstanceOf(UpstreamTimeoutException.class, root);
                    assertEquals("upstream request timeout", root.getMessage());
                } finally {
                    releaseSink.countDown(); releaseServer.countDown();
                    streaming.get(3, TimeUnit.SECONDS);
                }
            } finally { releaseSink.countDown(); releaseServer.countDown(); server.stop(0); }
        }
    }

    @Test void heartbeatWaitsForHeadersAndRunsOnTheRequestThread() throws Exception {
        var releaseHeaders = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var heartbeat = new CountDownLatch(1);
        var headers = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    releaseHeaders.await(3, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    releaseBody.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            var properties = new GatewayProperties(); properties.getUpstream().setHeartbeatIntervalMs(25);
            try (var client = new JdkHttpUpstream(properties)) {
                var task = workers.submit(() -> {
                    Thread caller = Thread.currentThread();
                    client.streamRawBlocking(request(server, 4000), new ChunkSink() {
                        public void onHeaders(int status, java.util.Map<String, java.util.List<String>> values) {
                            assertSame(caller, Thread.currentThread()); headers.countDown();
                        }
                        public void onChunk(String line) {
                            heartbeat.countDown();
                            assertSame(caller, Thread.currentThread());
                            assertEquals(0, headers.getCount());
                        }
                    });
                });
                try {
                    assertFalse(heartbeat.await(150, TimeUnit.MILLISECONDS), "must not commit before upstream headers");
                    releaseHeaders.countDown();
                    assertTrue(heartbeat.await(2, TimeUnit.SECONDS));
                } finally {
                    releaseHeaders.countDown(); releaseBody.countDown(); task.get(3, TimeUnit.SECONDS);
                }
            } finally { releaseHeaders.countDown(); releaseBody.countDown(); server.stop(0); }
        }
    }

    @Test void unsuccessfulBodyNeverEmitsHeartbeat() throws Exception {
        var headersSent = new CountDownLatch(1); var release = new CountDownLatch(1);
        var heartbeat = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(503, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write('x'); out.flush(); headersSent.countDown();
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            var properties = new GatewayProperties(); properties.getUpstream().setHeartbeatIntervalMs(25);
            try (var client = new JdkHttpUpstream(properties)) {
                var task = workers.submit(() -> client.streamRawBlocking(request(server, 3000), line -> heartbeat.countDown()));
                try {
                    assertTrue(headersSent.await(1, TimeUnit.SECONDS));
                    assertFalse(heartbeat.await(150, TimeUnit.MILLISECONDS));
                } finally { release.countDown(); }
                Throwable error = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
                while (error.getCause() != null) error = error.getCause();
                assertEquals(503, assertInstanceOf(UpstreamStatusException.class, error).status());
            } finally { release.countDown(); server.stop(0); }
        }
    }

    private static UpstreamRequest request(HttpServer server, int timeout) {
        return new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(),
                "", "m", true, timeout, "/", UpstreamMethod.POST);
    }
}
