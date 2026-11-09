import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Standalone OpenAI-compatible mock upstream for cross-gateway benchmarking.
 * Serves exactly what bench/k6-json.js and bench/k6-sse.js need, with the
 * same shape (and chunk counts) as llmgw4j's embedded MockUpstreamServer so
 * every gateway under test carries the same upstream payload work.
 *
 * Usage: java bench/mock/StandaloneMockUpstream.java [port]   (default 9099)
 */
public class StandaloneMockUpstream {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", StandaloneMockUpstream::handle);
        server.createContext("/chat/completions", StandaloneMockUpstream::handle);
        server.createContext("/v1/models", exchange -> writeJson(exchange, 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"mock-model\"}]}"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("standalone mock upstream on :" + port);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = body.contains("\"stream\"") && body.contains("true");
        exchange.getResponseHeaders().add("Content-Type",
                stream ? "text/event-stream" : "application/json");
        if (stream) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            sleep(20);
            for (int i = 0; i < 8; i++) {
                out.write(("data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + "\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(10);
            }
            out.write("data: {\"usage\":{\"completion_tokens\":8}}\n\n".getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            return;
        }
        sleep(20);
        writeJson(exchange, 200, "{\"id\":\"mock\",\"object\":\"chat.completion\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
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
}
