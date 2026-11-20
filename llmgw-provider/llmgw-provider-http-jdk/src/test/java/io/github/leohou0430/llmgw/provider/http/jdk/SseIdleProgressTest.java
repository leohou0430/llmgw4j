package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SseIdleProgressTest {

	@Test
	void asyncIdleReadTracksBytesInsideAnUnfinishedLine() throws Exception {
		verify(false, 200, false);
	}

	@Test
	void blockingIdleReadTracksBytesInsideAnUnfinishedLine() throws Exception {
		verify(true, 200, false);
	}

	@Test
	void asyncSlowErrorBodyUsesReadProgress() throws Exception {
		verify(false, 503, false);
	}

	@Test
	void blockingSlowErrorBodyUsesReadProgress() throws Exception {
		verify(true, 503, false);
	}

	@Test
	void asyncPartialLineThatStopsStillTimesOut() throws Exception {
		verify(false, 200, true);
	}

	@Test
	void blockingPartialLineThatStopsStillTimesOut() throws Exception {
		verify(true, 200, true);
	}

	@Test
	void emptyBatchesDoNotRefreshReadActivityOrCancelNormalCompletion() {
		var activity = new java.util.concurrent.atomic.AtomicLong(1);
		var delegate = java.net.http.HttpResponse.BodySubscribers.discarding();
		var body = new ReadActivitySubscriber<>(delegate, activity);
		body.onSubscribe(new Flow.Subscription() {
			public void request(long n) {
			}

			public void cancel() {
				fail("normal completion must not cancel transport");
			}
		});
		body.onNext(java.util.List.of());
		body.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(0)));
		assertEquals(1, activity.get());
		var bytes = java.nio.ByteBuffer.wrap(new byte[] { 'x' }).asReadOnlyBuffer();
		body.onNext(java.util.List.of(bytes));
		assertNotEquals(1, activity.get());
		assertEquals(0, bytes.position());
		body.onComplete();
		body.getBody().toCompletableFuture().join();
	}

	private void verify(boolean blocking, int status, boolean stalled) throws Exception {
		String expected = status == 200 ? "data: [DONE]\n\n" : "unavailable";
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(workers);
			server.createContext("/", exchange -> {
				exchange.sendResponseHeaders(status, 0);
				try (var out = exchange.getResponseBody()) {
					for (byte value : expected.getBytes(StandardCharsets.UTF_8)) {
						out.write(value);
						out.flush();
						Thread.sleep(stalled ? 600 : 50);
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
			properties.getUpstream().setIdleReadTimeoutMs(250);
			properties.getUpstream().setHeartbeatIntervalMs(0);
			try (var client = new JdkHttpUpstream(properties)) {
				var request = new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(), "", "m",
						true, 5000, "/", UpstreamMethod.POST);
				var result = new CompletableFuture<String>();
				if (blocking)
					workers.execute(() -> {
						try {
							var body = new StringBuilder();
							client.streamRawBlocking(request, body::append);
							result.complete(body.toString());
						}
						catch (Throwable error) {
							result.completeExceptionally(error);
						}
					});
				else
					client.streamRaw(request, HeaderListener.none()).subscribe(new Flow.Subscriber<>() {
						final StringBuilder body = new StringBuilder();

						public void onSubscribe(Flow.Subscription s) {
							s.request(Long.MAX_VALUE);
						}

						public void onNext(String line) {
							body.append(line);
						}

						public void onError(Throwable error) {
							result.completeExceptionally(error);
						}

						public void onComplete() {
							result.complete(body.toString());
						}
					});
				if (status == 200 && !stalled) {
					assertEquals(expected, result.get(3, TimeUnit.SECONDS),
							"continuous incoming bytes must not count as idle while waiting for LF");
				}
				else {
					Throwable error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
					while (error.getCause() != null)
						error = error.getCause();
					if (stalled) {
						assertInstanceOf(UpstreamTimeoutException.class, error);
						assertEquals("upstream idle read timeout", error.getMessage());
					}
					else
						assertEquals(503, assertInstanceOf(UpstreamStatusException.class, error).status());
				}
			}
			finally {
				server.stop(0);
			}
		}
	}

}
