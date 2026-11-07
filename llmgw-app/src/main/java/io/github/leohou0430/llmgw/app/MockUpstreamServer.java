package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * Deterministic mock upstream with configurable delay and SSE chunk count.
 * Used by unit tests and ALL benchmark runs so results are reproducible.
 *
 * <p>Failure injection for streaming error-contract tests (both markers are
 * inert for every benchmark payload, so benchmark numbers are unchanged):
 * <ul>
 *   <li>{@code mock-fail-after-N}: emit N chunks, then throw IOException so the
 *       connection breaks abnormally. Tests the thrown-error path.</li>
 *   <li>{@code mock-eof-after-N}: emit N chunks (N=0 means none), then close
 *       the chunked response CLEANLY with no {@code [DONE]} and no exception.
 *       Tests silent truncation, which a naive implementation mistakes for a
 *       successful stream.</li>
 *   <li>{@code mock-status-N}: answer with HTTP status N and a JSON error body,
 *       for BOTH stream and non-stream. Tests that a 4xx/5xx body is never
 *       forwarded as SSE data lines and always becomes a gateway 502.</li>
 *   <li>{@code mock-done-then-fail}: deliver the full stream including
 *       {@code [DONE]} and THEN break the connection. A completed protocol
 *       exchange must stay a success, with no extra error event.</li>
 *   <li>{@code mock-echo-hash}: the non-stream answer echoes a hash of the
 *       COMPLETE request body. Two requests with the same prompt but different
 *       sampling parameters must therefore produce different answers -- a
 *       cache keyed only on model+prompt would wrongly return the first one.</li>
 * </ul>
 */
@Component
public class MockUpstreamServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamServer.class);
    private static final String FAIL_MARKER = "mock-fail-after-";
    private static final String EOF_MARKER = "mock-eof-after-";
    private final GatewayProperties properties;
    private HttpServer server;

    public MockUpstreamServer(GatewayProperties properties) {
        this.properties = properties;
        if (!properties.getMock().isEnabled()) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(properties.getMock().getPort()), 0);
            server.createContext("/mock/v1/chat/completions", this::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            log.info("Mock upstream listening on port {}", properties.getMock().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("failed to start mock upstream", e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int forcedStatus = markerCount(body, "mock-status-", 0);
        if (forcedStatus > 0) {
            byte[] error = ("{\"error\":{\"message\":\"injected upstream status "
                    + forcedStatus + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(forcedStatus, error.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(error);
            }
            return;
        }
        boolean stream = body.contains("\"stream\"") && body.contains("true");
        if (stream) {
            int failAfter = failAfter(body);
            int eofAfter = eofAfter(body);
            boolean doneThenFail = body.contains("mock-done-then-fail");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // Deliberately NOT try-with-resources: the injector must be able to
            // abort a chunked response without writing its terminating chunk.
            OutputStream out = exchange.getResponseBody();
            sleep(properties.getMock().getDelayMs());
            int chunks = Math.max(1, properties.getMock().getChunks());
            if (eofAfter == 0) {
                // Clean EOF with zero frames: the hardest truncation to notice.
                out.close();
                return;
            }
            for (int i = 0; i < chunks; i++) {
                String json = "{\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + "\"}}]}";
                out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (failAfter > 0 && i + 1 == failAfter) {
                    throw new IOException("simulated upstream crash after " + failAfter + " chunks");
                }
                if (eofAfter > 0 && i + 1 == eofAfter) {
                    // Graceful close: writes the terminating HTTP chunk, so the
                    // client sees a perfectly normal EOF and NO exception.
                    out.close();
                    return;
                }
                sleep(properties.getMock().getChunkIntervalMs());
            }
            out.write(("data: {\"usage\":{\"completion_tokens\":" + chunks + "}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (doneThenFail) {
                // Terminator already on the wire. Abnormally drop the
                // connection afterwards without writing the terminating chunk.
                throw new IOException("simulated disconnect after [DONE]");
            }
            out.close();
        } else {
            String content = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
            String json = "{\"id\":\"mock\",\"object\":\"chat.completion\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                    + content + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** 0 = no marker; the two markers are mutually exclusive in practice. */
    private static int failAfter(String body) {
        return markerCount(body, FAIL_MARKER, 0);
    }

    /** -1 = no marker, 0 = close immediately, N = close after N chunks. */
    private static int eofAfter(String body) {
        return markerCount(body, EOF_MARKER, -1);
    }

    private static int markerCount(String body, String marker, int absent) {
        int index = body.indexOf(marker);
        if (index < 0) {
            return absent;
        }
        int start = index + marker.length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        if (end == start) {
            return absent;
        }
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (NumberFormatException e) {
            return absent;
        }
    }

    private static String hashPrefix(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.stop(0);
        }
    }
}
