package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SseLineLimitTest {
    @Test void asyncRejectsOversizedLineBeforeNewlineOrEof() throws Exception { oversized(false); }
    @Test void blockingRejectsOversizedLineBeforeNewlineOrEof() throws Exception { oversized(true); }

    private void oversized(boolean blocking) throws Exception {
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    byte[] bytes = new byte[8192];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    for (int i = 0; i < 256; i++) out.write(bytes);
                    out.flush();
                    release.await(5, TimeUnit.SECONDS);
                } catch (java.io.IOException cancelled) {
                    // Expected when the consumer rejects the untrusted line.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
            try (var client = new JdkHttpUpstream(new GatewayProperties())) {
                var request = new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "", "m", true, 5000, "/", UpstreamMethod.POST);
                Future<?> result;
                if (blocking) result = workers.submit(() -> client.streamRawBlocking(request, line -> fail("no complete line")));
                else {
                    var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE);
                    client.streamRaw(request).subscribe(probe);
                    result = probe.ended;
                }
                try {
                    var error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    assertEquals("upstream SSE line exceeds 1048576 bytes", root.getMessage());
                } finally { release.countDown(); }
            } finally { release.countDown(); server.stop(0); }
        }
    }
}
