package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamResponseLimitTest {
    @Test void blockingAndAsyncDownloadsRejectOversizedResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0),0);
        server.createContext("/", exchange -> {
            byte[] body = {0,1,2,3,4,5,6,7};
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var properties = new GatewayProperties();properties.setMaxResponseBytes(4);
        var upstream = new JdkHttpUpstream(properties);
        var request = new UpstreamRequest("", "http://127.0.0.1:" + server.getAddress().getPort(), "", "", false, 1000, "/", UpstreamMethod.GET);
        try {
            assertThrows(RuntimeException.class, () -> upstream.fetchRaw(request));
            assertThrows(RuntimeException.class, () -> upstream.fetchRawAsync(request).join());
        } finally {upstream.close();server.stop(0);}
    }
}
