package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1/T2.2 streaming download seam: a controlled loopback upstream proves fetchStream
 * delivers the body in bounded chunks (never one whole byte[]), rejects oversized
 * responses, surfaces upstream status errors, and stops on cancel. Pure JDK transport; no
 * engine involved.
 */
class FetchStreamTest {

	private static final class Collector implements Flow.Subscriber<ByteBuffer> {

		final List<byte[]> chunks = new ArrayList<>();

		final CountDownLatch done = new CountDownLatch(1);

		final AtomicReference<Throwable> error = new AtomicReference<>();

		Flow.Subscription subscription;

		long total;

		@Override
		public void onSubscribe(Flow.Subscription s) {
			this.subscription = s;
		}

		@Override
		public void onNext(ByteBuffer item) {
			byte[] copy = new byte[item.remaining()];
			item.get(copy);
			chunks.add(copy);
			total += copy.length;
		}

		@Override
		public void onError(Throwable t) {
			error.set(t);
			done.countDown();
		}

		@Override
		public void onComplete() {
			done.countDown();
		}

	}

	private static UpstreamRequest get(int port, String path) {
		return new UpstreamRequest("", "http://127.0.0.1:" + port, "", "", false, 5000, path, UpstreamMethod.GET);
	}

	@Test
	void streamsBodyInBoundedChunks() throws Exception {
		byte[] body = new byte[64 * 1024];
		for (int i = 0; i < body.length; i++)
			body[i] = (byte) (i % 251);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		JdkHttpUpstream upstream = new JdkHttpUpstream(new GatewayProperties());
		try {
			Collector c = new Collector();
			upstream.fetchStream(get(server.getAddress().getPort(), "/")).subscribe(c);
			c.subscription.request(Long.MAX_VALUE);
			assertTrue(c.done.await(10, TimeUnit.SECONDS), "stream must terminate");
			assertNull(c.error.get(), "no error on a normal download");
			assertEquals(body.length, c.total, "every byte delivered");
			assertTrue(c.chunks.size() >= 2, "body must arrive in multiple bounded chunks, not one whole buffer");
			byte[] reassembled = new byte[(int) c.total];
			int pos = 0;
			for (byte[] chunk : c.chunks) {
				System.arraycopy(chunk, 0, reassembled, pos, chunk.length);
				pos += chunk.length;
			}
			assertArrayEquals(body, reassembled);
		}
		finally {
			upstream.close();
			server.stop(0);
		}
	}

	@Test
	void rejectsOversizedResponse() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = { 0, 1, 2, 3, 4, 5, 6, 7 };
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		GatewayProperties properties = new GatewayProperties();
		properties.setMaxResponseBytes(4);
		JdkHttpUpstream upstream = new JdkHttpUpstream(properties);
		try {
			Collector c = new Collector();
			upstream.fetchStream(get(server.getAddress().getPort(), "/")).subscribe(c);
			c.subscription.request(Long.MAX_VALUE);
			assertTrue(c.done.await(10, TimeUnit.SECONDS));
			assertTrue(c.error.get() != null, "oversized response must error");
			assertTrue(c.total <= 4 + 16 * 1024, "must stop near the limit, not buffer the whole body");
		}
		finally {
			upstream.close();
			server.stop(0);
		}
	}

	@Test
	void surfacesUpstreamStatusError() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = "oops".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(500, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		JdkHttpUpstream upstream = new JdkHttpUpstream(new GatewayProperties());
		try {
			Collector c = new Collector();
			upstream.fetchStream(get(server.getAddress().getPort(), "/")).subscribe(c);
			c.subscription.request(Long.MAX_VALUE);
			assertTrue(c.done.await(10, TimeUnit.SECONDS));
			UpstreamStatusException ex = assertInstanceOf(UpstreamStatusException.class, c.error.get());
			assertEquals(500, ex.status());
		}
		finally {
			upstream.close();
			server.stop(0);
		}
	}

	@Test
	void cancelStopsDelivery() throws Exception {
		byte[] body = new byte[64 * 1024];
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		JdkHttpUpstream upstream = new JdkHttpUpstream(new GatewayProperties());
		try {
			Collector c = new Collector();
			upstream.fetchStream(get(server.getAddress().getPort(), "/")).subscribe(c);
			c.subscription.request(1);
			int afterFirst = c.chunks.size();
			c.subscription.cancel();
			c.subscription.request(Long.MAX_VALUE);
			assertEquals(afterFirst, c.chunks.size(), "no delivery after cancel");
			assertNull(c.error.get(), "cancel is silent, not an error");
		}
		finally {
			upstream.close();
			server.stop(0);
		}
	}

	private static void assertArrayEquals(byte[] expected, byte[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}

}