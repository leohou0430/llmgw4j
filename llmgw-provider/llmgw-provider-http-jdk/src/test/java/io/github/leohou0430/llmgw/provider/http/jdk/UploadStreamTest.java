package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T2.1/T2.2 streaming upload seam: a controlled loopback upstream proves the request body
 * is delivered byte-faithful through the streaming byte contract
 * (BodyPublishers.fromPublisher), not a whole-byte[] publisher.
 */
class UploadStreamTest {

	@Test
	void streamsUploadBodyByteFaithful() throws Exception {
		byte[] payload = new byte[256 * 1024];
		new Random(42).nextBytes(payload);
		AtomicReference<byte[]> received = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			received.set(exchange.getRequestBody().readAllBytes());
			byte[] ok = "{\"id\":\"file_1\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, ok.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(ok);
			}
		});
		server.start();
		JdkHttpUpstream upstream = new JdkHttpUpstream(new GatewayProperties());
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort();
			ByteStream body = ByteStream.ofByteArray(payload);
			UpstreamRequest request = new UpstreamRequest("", url, "", "", "", false, 5000, "/", UpstreamMethod.POST,
					Map.of(), body, payload.length);
			byte[] response = upstream.fetchRaw(request);
			assertArrayEquals(payload, received.get(), "upstream must receive every uploaded byte");
			assertEquals("{\"id\":\"file_1\"}", new String(response, StandardCharsets.UTF_8));
		}
		finally {
			upstream.close();
			server.stop(0);
		}
	}

}