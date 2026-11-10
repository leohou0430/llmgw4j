package io.github.leohou0430.llmgw.governance.inmemory;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HttpGuardrailTest {
    @Test void outagesAreUnavailableAndNeverCachedAsApproval() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"action\":\"ALLOW\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(calls.incrementAndGet() == 1 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p = new GatewayProperties();
            p.getGuardrails().setHttpUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var guard = new HttpGuardrail(p);
            var request = ProtocolCodec.parse(ProtocolKind.CHAT, "{\"model\":\"a\",\"messages\":[{\"content\":\"hello\"}]}");
            assertEquals("UNAVAILABLE", guard.inspectRequest(request).action().name());
            assertEquals("ALLOW", guard.inspectRequest(request).action().name());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
    @Test void missingActionIsNotApproval() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var p = new GatewayProperties();p.getGuardrails().setHttpUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var guard = new HttpGuardrail(p);
            var request = ProtocolCodec.parse(ProtocolKind.CHAT, "{\"model\":\"a\",\"messages\":[]}");
            assertEquals("UNAVAILABLE", guard.inspectRequest(request).action().name());
        } finally { server.stop(0); }
    }
    @Test void decisionsForDifferentModelsDoNotShareCache() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = input.contains("\"model\":\"b\"") ? "DENY" : "ALLOW";
            byte[] body = ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var p = new GatewayProperties();p.getGuardrails().setHttpUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var guard = new HttpGuardrail(p);
            assertEquals("ALLOW", guard.inspectRequest(ProtocolCodec.parse(ProtocolKind.CHAT, "{\"model\":\"a\",\"messages\":[{\"content\":\"hello\"}]}")).action().name());
            assertEquals("DENY", guard.inspectRequest(ProtocolCodec.parse(ProtocolKind.CHAT, "{\"model\":\"b\",\"messages\":[{\"content\":\"hello\"}]}")).action().name());
        } finally { server.stop(0); }
    }
}
