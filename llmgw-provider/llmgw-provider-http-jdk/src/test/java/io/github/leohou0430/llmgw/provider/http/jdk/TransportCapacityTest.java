package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportCapacityTest {

	@Test
	void uniqueResourcePathsCannotGrowTheUriCacheWithoutBound() throws Exception {
		try (var client = new JdkHttpUpstream(new GatewayProperties())) {
			var resolve = JdkHttpUpstream.class.getDeclaredMethod("targetUri", String.class, String.class);
			resolve.setAccessible(true);
			for (int i = 0; i < 10000; i++)
				assertEquals("/v1/files/id-" + i + "/content",
						((URI) resolve.invoke(client, "http://127.0.0.1/v1", "/files/id-" + i + "/content")).getPath());
			var field = JdkHttpUpstream.class.getDeclaredField("targetUris");
			field.setAccessible(true);
			assertTrue(((Map<?, ?>) field.get(client)).size() <= 1024,
					"10k distinct resource paths must not stay retained for the client's lifetime");
		}
	}

	@Test
	void vtErrorResponseMustRespectByteLimitBeforeUpstreamEof() throws Exception {
		var release = new CountDownLatch(1);
		var sent = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(workers);
			server.createContext("/", e -> {
				e.sendResponseHeaders(503, 0);
				try (var out = e.getResponseBody()) {
					out.write(("x".repeat(128) + "\n").repeat(32).getBytes());
					out.flush();
					sent.countDown();
					release.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				finally {
					e.close();
				}
			});
			server.start();
			var properties = new GatewayProperties();
			properties.setMaxResponseBytes(1024);
			try (var client = new JdkHttpUpstream(properties)) {
				var request = request(server);
				var result = workers
					.submit(() -> assertThrows(RuntimeException.class, () -> client.streamRawBlocking(request, line -> {
					})));
				try {
					assertTrue(sent.await(2, TimeUnit.SECONDS));
					assertNotNull(result.get(1, TimeUnit.SECONDS),
							"must reject an oversized error while its body is still open");
				}
				finally {
					release.countDown();
					result.get(5, TimeUnit.SECONDS);
				}
			}
			finally {
				release.countDown();
				server.stop(0);
			}
		}
	}

	@Test
	void boundedVtErrorKeepsStatusRetryAndCallerThreadHeaders() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			byte[] body = "denied".getBytes();
			e.getResponseHeaders().set("Retry-After", "2");
			e.sendResponseHeaders(429, body.length);
			try (var out = e.getResponseBody()) {
				out.write(body);
			}
			finally {
				e.close();
			}
		});
		server.start();
		try (var client = new JdkHttpUpstream(new GatewayProperties())) {
			Thread caller = Thread.currentThread();
			var headers = new java.util.concurrent.atomic.AtomicInteger();
			var failure = assertThrows(RuntimeException.class,
					() -> client.streamRawBlocking(request(server), new ChunkSink() {
						public void onHeaders(int status, Map<String, java.util.List<String>> values) {
							assertSame(caller, Thread.currentThread());
							headers.incrementAndGet();
						}

						public void onChunk(String line) {
							fail("error body must not become SSE");
						}
					}));
			Throwable root = failure;
			while (root.getCause() != null)
				root = root.getCause();
			var error = assertInstanceOf(UpstreamStatusException.class, root);
			assertEquals(429, error.status());
			assertEquals(2000, error.retryAfterMs());
			assertEquals("upstream status 429: denied", error.getMessage());
			assertEquals(1, headers.get());
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void asyncIdleBudgetStartsAtHeadersRatherThanRequestStart() throws Exception {
		checkIdleBudget(true);
	}

	@Test
	void blockingIdleBudgetStartsAtHeadersRatherThanRequestStart() throws Exception {
		checkIdleBudget(false);
	}

	private void checkIdleBudget(boolean async) throws Exception {
		{
			try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
				var headersSeen = new CountDownLatch(1);
				var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				server.setExecutor(workers);
				server.createContext("/", e -> {
					try {
						Thread.sleep(625);
						e.getResponseHeaders().set("Content-Type", "text/event-stream");
						e.sendResponseHeaders(200, 0);
						e.getResponseBody().flush();
						headersSeen.await(2, TimeUnit.SECONDS);
						Thread.sleep(200);
						try (var out = e.getResponseBody()) {
							out.write("data: [DONE]\n\n".getBytes());
						}
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					}
					finally {
						e.close();
					}
				});
				server.start();
				var properties = new GatewayProperties();
				properties.getUpstream().setIdleReadTimeoutMs(300);
				try (var client = new JdkHttpUpstream(properties)) {
					var request = request(server);
					if (async) {
						var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE);
						client.streamRaw(request, (status, headers) -> headersSeen.countDown()).subscribe(probe);
						assertDoesNotThrow(() -> probe.ended.get(3, TimeUnit.SECONDS));
						assertEquals("data: [DONE]\n", probe.values.poll());
					}
					else {
						assertDoesNotThrow(() -> client.streamRawBlocking(request, new ChunkSink() {
							public void onHeaders(int status, Map<String, java.util.List<String>> headers) {
								headersSeen.countDown();
							}

							public void onChunk(String line) {
							}
						}));
					}
				}
				finally {
					server.stop(0);
				}
			}
		}
	}

	@Test
	void asyncWholeRequestDeadlineIncludesAnActiveStreamBody() throws Exception {
		checkWholeDeadline(true, true);
	}

	@Test
	void blockingWholeRequestDeadlineIncludesAnActiveStreamBody() throws Exception {
		checkWholeDeadline(false, true);
	}

	@Test
	void asyncJsonWholeRequestDeadlineIncludesItsBody() throws Exception {
		checkWholeDeadline(true, false);
	}

	@Test
	void blockingJsonWholeRequestDeadlineIncludesItsBody() throws Exception {
		checkWholeDeadline(false, false);
	}

	private void checkWholeDeadline(boolean async, boolean streaming) throws Exception {
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(workers);
			server.createContext("/", e -> {
				e.getResponseHeaders().set("Content-Type", "text/event-stream");
				e.sendResponseHeaders(200, 0);
				try (var out = e.getResponseBody()) {
					for (int i = 0; i < 25; i++) {
						out.write("data: chunk\n\n".getBytes());
						out.flush();
						Thread.sleep(80);
					}
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				catch (java.io.IOException cancelled) {
				}
				finally {
					e.close();
				}
			});
			server.start();
			var client = new JdkHttpUpstream(new GatewayProperties());
			try {
				var request = new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(), "", "m",
						true, 400, "/", UpstreamMethod.POST);
				if (!streaming) {
					var result = async ? client.completeRawAsync(request)
							: workers.submit(() -> client.completeRaw(request));
					assertDeadline(assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS)));
				}
				else if (async) {
					var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE);
					client.streamRaw(request).subscribe(probe);
					try {
						assertDeadline(
								assertThrows(ExecutionException.class, () -> probe.ended.get(1, TimeUnit.SECONDS)));
					}
					finally {
						probe.subscription.cancel();
					}
				}
				else {
					var result = workers.submit(() -> {
						try {
							client.streamRawBlocking(request, line -> {
							});
							return null;
						}
						finally {
							assertFalse(Thread.currentThread().isInterrupted(),
									"own deadline must not leak an interrupt to the caller");
						}
					});
					assertDeadline(assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS)));
				}
			}
			finally {
				server.stop(0);
				client.close();
			}
		}
	}

	private static void assertDeadline(ExecutionException error) {
		Throwable root = error;
		while (root.getCause() != null)
			root = root.getCause();
		assertInstanceOf(UpstreamTimeoutException.class, root);
		assertEquals("upstream request timeout", root.getMessage());
	}

	@Test
	void deadlinesAreRemovedAndSchedulersBelongToEachTransport() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			byte[] body = "ok".getBytes();
			e.sendResponseHeaders(200, body.length);
			try (var out = e.getResponseBody()) {
				out.write(body);
			}
			finally {
				e.close();
			}
		});
		server.start();
		var first = new JdkHttpUpstream(new GatewayProperties());
		try (var second = new JdkHttpUpstream(new GatewayProperties())) {
			var field = JdkHttpUpstream.class.getDeclaredField("watchdog");
			field.setAccessible(true);
			var one = (ScheduledThreadPoolExecutor) field.get(first);
			var two = (ScheduledThreadPoolExecutor) field.get(second);
			assertNotSame(one, two);
			assertEquals("ok", first.completeRaw(request(server)));
			assertTrue(one.getQueue().isEmpty(), "successful blocking request must remove its deadline");
			first.close();
			assertTrue(one.awaitTermination(1, TimeUnit.SECONDS));
			assertFalse(two.isShutdown());
			for (int i = 0; i < 20; i++)
				assertEquals("ok", second.completeRawAsync(request(server)).get(2, TimeUnit.SECONDS));
			// Completion is delivered before the source callback cancels its timer.
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
			while (!two.getQueue().isEmpty() && System.nanoTime() < end)
				Thread.sleep(5);
			assertTrue(two.getQueue().isEmpty(), "completed async requests must not accumulate timer tasks");
		}
		finally {
			first.close();
			server.stop(0);
		}
	}

	private static UpstreamRequest request(HttpServer server) {
		return new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(), "", "m", true, 3000, "/",
				UpstreamMethod.POST);
	}

}
