package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Deterministic mock upstream speaking ALL FIVE gateway protocols
 * (chat / responses / messages / images / videos), with configurable delay and
 * SSE chunk count. Used by smoke scripts and ALL benchmark runs so results are
 * reproducible.
 *
 * <p>Each streaming protocol emits its OWN delta and terminator frames -- the
 * gateway must forward them verbatim and never fabricate a terminator:
 * <ul>
 *   <li>chat: {@code data: {...delta...}} ... {@code data: [DONE]}</li>
 *   <li>responses: {@code event: response.output_text.delta} ...
 *       {@code event: response.completed} (usage inside response.usage)</li>
 *   <li>messages: {@code event: content_block_delta} ...
 *       {@code data: message_delta} (usage) ... {@code event: message_stop}</li>
 * </ul>
 *
 * <p>Failure injection for streaming error-contract tests (all markers are
 * inert for every benchmark payload, so benchmark numbers are unchanged):
 * <ul>
 *   <li>{@code mock-fail-after-N}: emit N chunks, then throw IOException so the
 *       connection breaks abnormally. Tests the thrown-error path.</li>
 *   <li>{@code mock-eof-after-N}: emit N chunks (N=0 means none), then close
 *       the chunked response CLEANLY with no terminator and no exception.
 *       Tests silent truncation, which a naive implementation mistakes for a
 *       successful stream.</li>
 *   <li>{@code mock-status-N}: answer with HTTP status N and a JSON error body,
 *       for BOTH stream and non-stream POSTs. Tests that a 4xx/5xx body is
 *       never forwarded as SSE data lines and always becomes a gateway 502.</li>
 *   <li>{@code mock-done-then-fail}: deliver the full stream including the
 *       protocol terminator and THEN break the connection. A completed protocol
 *       exchange must stay a success, with no extra error event.</li>
 *   <li>{@code mock-echo-hash}: the non-stream answer echoes a hash of the
 *       COMPLETE request body. Two requests with the same prompt but different
 *       sampling parameters must therefore produce different answers -- a
 *       cache keyed only on model+prompt would wrongly return the first one.</li>
 * </ul>
 *
 * <p>Video tasks are asynchronous: create mints a task id, poll returns
 * queued/in_progress/completed based on elapsed time, and content is served
 * (video/mp4) only once completed. Unknown task ids 404 -- which is what makes
 * a second gateway instance (with its own mock) a real sticky-routing probe.
 */
@Component
public class MockUpstreamServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamServer.class);
    private static final String FAIL_MARKER = "mock-fail-after-";
    private static final String EOF_MARKER = "mock-eof-after-";
    private static final int MAX_IMAGES = 10;
    /** Task sits in "queued" for this long, then "in_progress", then completes. */
    private static final long QUEUED_MS = 400;
    private static final long READY_MS = 1_200;

    private final GatewayProperties properties;
    private final Map<String, VideoTask> videoTasks = new ConcurrentHashMap<>();
    private final AtomicLong videoIds = new AtomicLong();
    private HttpServer server;

    public MockUpstreamServer(GatewayProperties properties) {
        this.properties = properties;
        if (!properties.getMock().isEnabled()) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(properties.getMock().getPort()), 0);
            server.createContext("/mock/v1/chat/completions", this::handle);
            server.createContext("/mock/v1/responses", this::handle);
            server.createContext("/mock/v1/messages", this::handle);
            server.createContext("/mock/v1/images/generations", this::handle);
            server.createContext("/mock/v1/videos", this::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            log.info("Mock upstream listening on port {}", properties.getMock().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("failed to start mock upstream", e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/mock/v1/videos/")) {
            handleVideoFetch(exchange, path);
            return;
        }
        if (path.endsWith("/videos")) {
            handleVideoCreate(exchange);
            return;
        }
        if (path.endsWith("/responses")) {
            handleResponses(exchange);
            return;
        }
        if (path.endsWith("/messages")) {
            handleMessages(exchange);
            return;
        }
        if (path.endsWith("/images/generations")) {
            handleImages(exchange);
            return;
        }
        handleChat(exchange);
    }

    // ---------------------------------------------------------------- chat

    private void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (denyWithStatus(exchange, body)) {
            return;
        }
        boolean stream = body.contains("\"stream\"") && body.contains("true");
        if (stream) {
            int chunks = Math.max(1, properties.getMock().getChunks());
            IntFunction<String> delta = i -> {
                String json = "{\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + "\"}}]}";
                return "data: " + json + "\n\n";
            };
            String usage = "data: {\"usage\":{\"completion_tokens\":" + chunks + "}}\n\n";
            writeStream(exchange, body, delta, usage, "data: [DONE]\n\n");
            return;
        }
        String content = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
        String json = "{\"id\":\"mock\",\"object\":\"chat.completion\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
        writeJson(exchange, 200, json);
    }

    // ------------------------------------------------------------ responses

    private void handleResponses(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (denyWithStatus(exchange, body)) {
            return;
        }
        boolean stream = body.contains("\"stream\"") && body.contains("true");
        if (stream) {
            int chunks = Math.max(1, properties.getMock().getChunks());
            IntFunction<String> delta = i -> {
                String json = "{\"type\":\"response.output_text.delta\","
                        + "\"delta\":\"tok" + i + "\"}";
                return "event: response.output_text.delta\ndata: " + json + "\n\n";
            };
            String completed = "event: response.completed\ndata: {\"type\":\"response.completed\","
                    + "\"response\":{\"id\":\"resp_mock\",\"usage\":{\"input_tokens\":1,"
                    + "\"output_tokens\":" + chunks + "}}}\n\n";
            writeStream(exchange, body, delta, null, completed);
            return;
        }
        String text = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
        String json = "{\"id\":\"resp_mock\",\"object\":\"response\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"" + text + "\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        writeJson(exchange, 200, json);
    }

    // ------------------------------------------------------------- messages

    private void handleMessages(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (denyWithStatus(exchange, body)) {
            return;
        }
        boolean stream = body.contains("\"stream\"") && body.contains("true");
        if (stream) {
            int chunks = Math.max(1, properties.getMock().getChunks());
            IntFunction<String> delta = i -> {
                String json = "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"tok" + i + "\"}}";
                return "event: content_block_delta\ndata: " + json + "\n\n";
            };
            String usage = "data: {\"type\":\"message_delta\","
                    + "\"delta\":{\"stop_reason\":\"end_turn\"},"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":" + chunks + "}}\n\n";
            String stop = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            writeStream(exchange, body, delta, usage, stop);
            return;
        }
        String text = body.contains("mock-echo-hash") ? "echo-" + hashPrefix(body) : "ok";
        String json = "{\"id\":\"msg_mock\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"model\":\"mock\",\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        writeJson(exchange, 200, json);
    }

    // --------------------------------------------------------------- images

    private void handleImages(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (denyWithStatus(exchange, body)) {
            return;
        }
        JsonNode root = tryParse(body);
        int n = Math.min(MAX_IMAGES, Math.max(1, root.path("n").asInt(1)));
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                data.append(',');
            }
            String b64 = Base64.getEncoder()
                    .encodeToString(("mock-image-" + i + "-" + hashPrefix(body))
                            .getBytes(StandardCharsets.UTF_8));
            data.append("{\"b64_json\":\"").append(b64).append("\"}");
        }
        String json = "{\"created\":1730000000,\"data\":[" + data + "]}";
        writeJson(exchange, 200, json);
    }

    // --------------------------------------------------------------- videos

    private void handleVideoCreate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (denyWithStatus(exchange, body)) {
            return;
        }
        JsonNode root = tryParse(body);
        String id = "video-" + videoIds.incrementAndGet();
        String model = root.path("model").asText("mock");
        videoTasks.put(id, new VideoTask(model));
        writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\"queued\","
                + "\"model\":\"" + model + "\"}");
    }

    private void handleVideoFetch(HttpExchange exchange, String path) throws IOException {
        String id = path.substring("/mock/v1/videos/".length());
        boolean content = id.endsWith("/content");
        if (content) {
            id = id.substring(0, id.length() - "/content".length());
        }
        VideoTask task = videoTasks.get(id);
        if (task == null) {
            // Unknown upstream: exactly what a second gateway instance sees
            // when polling another instance's task -- the sticky proof.
            writeJson(exchange, 404, "{\"error\":{\"message\":\"unknown video task\"}}");
            return;
        }
        if (content) {
            if (!"completed".equals(task.status(System.currentTimeMillis()))) {
                writeJson(exchange, 409, "{\"error\":{\"message\":\"video not ready\"}}");
                return;
            }
            byte[] bytes = ("mock-video-content-" + id + "-").repeat(16)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }
        writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\""
                + task.status(System.currentTimeMillis()) + "\",\"model\":\"" + task.model()
                + "\",\"progress\":50}");
    }

    // ----------------------------------------------------- shared plumbing

    /**
     * The streaming skeleton is protocol-independent: N delta frames with
     * failure injection, then a usage frame, then the protocol terminator.
     */
    private void writeStream(HttpExchange exchange, String body, IntFunction<String> deltaFrame,
                             String usageFrame, String terminatorFrame) throws IOException {
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
            out.write(deltaFrame.apply(i).getBytes(StandardCharsets.UTF_8));
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
        if (usageFrame != null) {
            out.write(usageFrame.getBytes(StandardCharsets.UTF_8));
        }
        out.write(terminatorFrame.getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (doneThenFail) {
            // Terminator already on the wire. Abnormally drop the connection
            // afterwards without writing the terminating HTTP chunk.
            throw new IOException("simulated disconnect after protocol terminator");
        }
        out.close();
    }

    /** Answers with the injected status; true = handled, false = no marker. */
    private boolean denyWithStatus(HttpExchange exchange, String body) throws IOException {
        int forcedStatus = markerCount(body, "mock-status-", 0);
        if (forcedStatus <= 0) {
            return false;
        }
        writeJson(exchange, forcedStatus,
                "{\"error\":{\"message\":\"injected upstream status " + forcedStatus + "\"}}");
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Malformed bodies fall back to an empty object, never a 500. */
    private static JsonNode tryParse(String body) {
        try {
            return ProtocolCodec.tryParse(body);
        } catch (RuntimeException e) {
            return ProtocolCodec.tryParse("{}");
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

    private static final class VideoTask {
        private final String model;
        private final long createdAt = System.currentTimeMillis();

        private VideoTask(String model) {
            this.model = model;
        }

        private String status(long now) {
            long age = now - createdAt;
            if (age < QUEUED_MS) {
                return "queued";
            }
            return age < READY_MS ? "in_progress" : "completed";
        }

        private String model() {
            return model;
        }
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.stop(0);
        }
    }
}
