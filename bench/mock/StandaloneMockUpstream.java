import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/** Shared benchmark fixture: port, first delay ms, chunk interval ms, optional provider key. */
public class StandaloneMockUpstream {
    private static volatile int delayMs = 20;
    private static volatile int chunkMs = 10;
    private static String providerKey = "";
    private static final LongAdder requests = new LongAdder();
    private static final Pattern STREAM = Pattern.compile("\\\"stream\\\"\\s*:\\s*true\\b");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;
        if (args.length > 1) delayMs = Math.max(0, Integer.parseInt(args[1]));
        if (args.length > 2) chunkMs = Math.max(0, Integer.parseInt(args[2]));
        if (args.length > 3) providerKey = args[3];
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.createContext("/v1/chat/completions", StandaloneMockUpstream::handle);
        server.createContext("/chat/completions", StandaloneMockUpstream::handle);
        server.createContext("/v1/models", exchange -> {
            if (authorized(exchange)) writeJson(exchange, 200,
                    "{\"object\":\"list\",\"data\":[{\"id\":\"qwen-plus\"}]}");
        });
        server.createContext("/__benchmark/stats", exchange -> {
            if (authorized(exchange)) writeJson(exchange, 200, "{\"requests\":" + requests.sum() + "}");
        });
        server.createContext("/__benchmark/control", exchange -> {
            if (!authorized(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{}"); return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                String[] fields = body.split(",");
                delayMs = Math.max(0, Integer.parseInt(fields[0]));
                chunkMs = Math.max(0, Integer.parseInt(fields[1]));
                writeJson(exchange, 200, "{\"delay_ms\":" + delayMs + ",\"chunk_ms\":" + chunkMs + "}");
            } catch (RuntimeException error) { writeJson(exchange, 400, "{}"); }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("standalone mock upstream on :" + port);
    }

    private static boolean authorized(HttpExchange exchange) throws IOException {
        if (providerKey.isEmpty() || ("Bearer " + providerKey).equals(exchange.getRequestHeaders().getFirst("Authorization"))) return true;
        writeJson(exchange, 401, "{\"error\":{\"message\":\"invalid upstream key\"}}");
        return false;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        requests.increment();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = STREAM.matcher(body).find();
        if (!stream) {
            sleep(delayMs);
            writeJson(exchange, 200, "{\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"qwen-plus\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            sleep(delayMs);
            for (int i = 0; i < 8; i++) {
                String frame = "data: {\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"qwen-plus\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + "\"},\"finish_reason\":"
                        + (i == 7 ? "\"stop\"" : "null") + "}]}\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8)); out.flush(); sleep(chunkMs);
            }
            out.write(("data: {\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"qwen-plus\","
                    + "\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":8,\"total_tokens\":9}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static void sleep(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
