package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.8 seam: the streaming download ({@code fetchStream}) must harden its per-read idle
 * timeout. A upstream that sends headers and a partial body then stalls must surface
 * {@code upstream idle read timeout} (not hang on the blocking read); continuous bytes
 * must complete normally without a false timeout, and normal completion must not cancel
 * the transport.
 */
class FetchStreamIdleTimeoutTest {

	@Test
	void stalledUpstreamBodyTimesOutOnIdleRead() throws Exception {
		verify(250, true, 2000);
	}

	@Test
	void continuousUpstreamBodyCompletesWithoutIdleTimeout() throws Exception {
		verify(250, false, 0);
	}

	/**
	 * @param idleMs configured idle-read timeout
	 * @param stalled whether the upstream stalls after the first bytes
	 * @param stallMs how long the upstream holds the connection open
	 */
	private void verify(int idleMs, boolean stalled, int stallMs) throws Exception {
		byte[] payload = "data: hello\n\n".getBytes(StandardCharsets.UTF_8);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(workers);
			var exchanges = new AtomicInteger();
			server.createContext("/", exchange -> {
				exchanges.incrementAndGet();
				exchange.sendResponseHeaders(200, 0);
				try (var out = exchange.getResponseBody()) {
					out.write(payload, 0, 4);
					out.flush();
					if (stalled) {
						Thread.sleep(stallMs);
					}
					else {
						out.write(payload, 4, payload.length - 4);
						out.flush();
					}
				}
				catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
				finally {
					exchange.close();
				}
			});
			server.start();
			var properties = new GatewayProperties();
			properties.getUpstream().setIdleReadTimeoutMs(idleMs);
			properties.getUpstream().setFirstByteTimeoutMs(0);
			properties.getUpstream().setHeartbeatIntervalMs(0);
			try (var client = new JdkHttpUpstream(properties)) {
				var request = new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(), "", "m",
						false, 5000, "/", UpstreamMethod.GET);
				var result = new CompletableFuture<byte[]>();
				var chunks = new AtomicInteger();
				client.fetchStream(request).subscribe(new Flow.Subscriber<>() {
					final ByteArrayOutputStream body = new ByteArrayOutputStream();

					public void onSubscribe(Flow.Subscription s) {
						s.request(Long.MAX_VALUE);
					}

					public void onNext(ByteBuffer b) {
						chunks.incrementAndGet();
						byte[] t = new byte[b.remaining()];
						b.get(t);
						body.writeBytes(t);
					}

					public void onError(Throwable error) {
						result.completeExceptionally(error);
					}

					public void onComplete() {
						result.complete(body.toByteArray());
					}
				});
				if (stalled) {
					Throwable error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
					while (error.getCause() != null)
						error = error.getCause();
					assertInstanceOf(UpstreamTimeoutException.class, error);
					assertEquals("upstream idle read timeout", error.getMessage());
					assertTrue(chunks.get() >= 1, "the partial body before the stall must still be delivered");
				}
				else {
					assertArrayEquals(payload, result.get(3, TimeUnit.SECONDS));
				}
			}
			finally {
				server.stop(0);
			}
		}
	}

}