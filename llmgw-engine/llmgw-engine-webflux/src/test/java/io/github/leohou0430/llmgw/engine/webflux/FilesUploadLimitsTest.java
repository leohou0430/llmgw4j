package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.7 seam (WebFlux): a declared Content-Length over the cap is rejected with 413 before
 * the client body is subscribed; an unknown length is rejected cumulatively by the
 * scanner; and parallel uploads draw from the same total concurrency pool enforced by
 * {@link WebfluxCapacityAdmission} (a saturated pool 503s, and every terminal path
 * returns its lease exactly once).
 */
class FilesUploadLimitsTest {

	private static final String B = "----llmgwBoundary7ma4d9";

	private static byte[] multipart(byte[] fileContent) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(("--" + B + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes("assistants".getBytes(StandardCharsets.UTF_8));
		out.writeBytes(("\r\n--" + B + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		out.writeBytes(fileContent);
		out.writeBytes(("\r\n--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private static byte[] pattern(int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i % 251);
		}
		return b;
	}

	@Test
	void knownLengthOverCapIs413WithoutSubscribingBody() {
		byte[] body = multipart(pattern(10));
		var subscribed = new AtomicBoolean();
		try (var fixture = new Fixture(100, 1, false)) {
			var alloc = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
			Flux<DataBuffer> clientBody = Flux.just((DataBuffer) alloc.wrap(body))
				.doOnSubscribe(s -> subscribed.set(true));
			var response = fixture.post(clientBody, "multipart/form-data; boundary=" + B, "101");
			assertEquals(413, response.getStatusCode().value());
			assertFalse(subscribed.get(), "a declared over-cap length must be rejected before the body is subscribed");
			assertEquals(0, fixture.provider.rawCalls.get());
		}
	}

	@Test
	void unknownLengthOverCapIs413Cumulatively() {
		byte[] body = multipart(pattern(40000));
		try (var fixture = new Fixture(100, 4, false)) { // whole-multipart bound = 100
															// bytes
			var response = fixture.post(body, "multipart/form-data; boundary=" + B, null);
			assertEquals(413, response.getStatusCode().value());
			assertEquals(0, fixture.provider.rawCalls.get(), "an over-limit body must never reach the upstream");
		}
	}

	@Test
	void fourParallelUploadsShareTotalConcurrencyAndReturnLeases() throws Exception {
		try (var fixture = new Fixture(4 << 20, 4, true)) {
			var started = new CountDownLatch(4);
			var release = new CountDownLatch(1);
			fixture.provider.gate = release;
			fixture.provider.started = started;
			byte[] body = multipart(pattern(10));
			var futures = new ArrayList<CompletableFuture<Void>>();
			for (int i = 0; i < 4; i++) {
				futures.add(fixture.start(body));
			}
			assertTrue(started.await(5, TimeUnit.SECONDS), "all four uploads must reach the upstream");
			// Pool of FOUR is saturated: a fifth upload is rejected with 503.
			var denied = fixture.post(body, "multipart/form-data; boundary=" + B, null);
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, denied.getStatusCode());
			release.countDown();
			for (var f : futures) {
				f.get(5, TimeUnit.SECONDS);
			}
			// Every lease returned: a fresh upload succeeds again.
			var ok = fixture.post(body, "multipart/form-data; boundary=" + B, null);
			assertEquals(HttpStatus.OK, ok.getStatusCode(),
					"leases must return to zero after parallel uploads complete");
		}
	}

	static final class LatchedProvider implements UpstreamProvider {

		final AtomicInteger rawCalls = new AtomicInteger();

		volatile CountDownLatch gate;

		volatile CountDownLatch started;

		@Override
		public String id() {
			return "latched";
		}

		@Override
		public Flow.Publisher<String> streamRaw(UpstreamRequest r, HeaderListener l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void streamRawBlocking(UpstreamRequest r, ChunkSink s) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String completeRaw(UpstreamRequest r, HeaderListener l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest r) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] fetchRaw(UpstreamRequest req) {
			rawCalls.incrementAndGet();
			drain(req.body());
			if (started != null) {
				started.countDown();
			}
			if (gate != null) {
				try {
					assertTrue(gate.await(5, TimeUnit.SECONDS), "gate released");
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}
			return "{\"id\":\"f\",\"object\":\"file\"}".getBytes(StandardCharsets.UTF_8);
		}

		private static void drain(ByteStream body) {
			CountDownLatch done = new CountDownLatch(1);
			body.subscribe(new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription s) {
					s.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(ByteBuffer b) {
				}

				@Override
				public void onError(Throwable t) {
					done.countDown();
				}

				@Override
				public void onComplete() {
					done.countDown();
				}
			});
			try {
				assertTrue(done.await(5, TimeUnit.SECONDS), "body drained");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class WebConfig extends WebFluxConfigurationSupport {

	}

	static class Fixture implements AutoCloseable {

		final LatchedProvider provider = new LatchedProvider();

		final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);

		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

		final HttpHandler handler;

		Fixture(long maxRequestBytes, int maxConcurrent, boolean latched) {
			var properties = new GatewayProperties();
			properties.setMaxConcurrentRequests(maxConcurrent);
			properties.setMaxRequestBytes(maxRequestBytes);
			var route = new Route("test", "*", "", "http://unused", "", 1, List.of());
			var router = new Router() {
				public Route resolve(String model) {
					return route;
				}

				public Route resolve(ProtocolKind kind, String model) {
					return route;
				}

				public Route byId(String id) {
					return route;
				}

				public List<Route> fallbacks(Route primary) {
					return List.of();
				}

				public List<Route> all() {
					return List.of(route);
				}
			};
			var chain = new GovernanceChain() {
				public GatewayDecision enter(String token, ParsedRequest request, Route target) {
					return GatewayDecision.allow("tenant", request, target);
				}

				public Optional<String> lookupCache(String key, ParsedRequest request) {
					return Optional.empty();
				}

				public void cache(String key, ParsedRequest request, String body) {
				}

				public void finish(GatewayContext context, boolean success) {
				}

				public Map<String, Object> snapshot() {
					return Map.of();
				}
			};
			var headers = new HeaderPolicy() {
				public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) {
					return Map.of();
				}

				public List<String> toClient(Map<String, List<String>> values) {
					return List.of();
				}
			};
			context.getEnvironment().setActiveProfiles("webflux");
			context.registerBean(GatewayProperties.class, () -> properties);
			context.register(WebConfig.class, WebfluxEngineConfiguration.class);
			context.registerBean(WebfluxGatewayController.class, () -> new WebfluxGatewayController(chain, router,
					provider, CircuitBreaker.noop(), properties, headers, tasks));
			context.refresh();
			handler = WebHttpHandlerBuilder.applicationContext(context).build();
		}

		CompletableFuture<Void> start(byte[] body) {
			var alloc = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
			return handler
				.handle(MockServerHttpRequest.post("/v1/files")
					.header("Authorization", "Bearer test-token")
					.header("Content-Type", "multipart/form-data; boundary=" + B)
					.body(Flux.just((DataBuffer) alloc.wrap(body))), new MockServerHttpResponse())
				.toFuture();
		}

		MockServerHttpResponse post(byte[] body, String contentType, String contentLength) {
			var alloc = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
			return post(Flux.just((DataBuffer) alloc.wrap(body)), contentType, contentLength);
		}

		MockServerHttpResponse post(Flux<DataBuffer> body, String contentType, String contentLength) {
			var builder = MockServerHttpRequest.post("/v1/files")
				.header("Authorization", "Bearer test-token")
				.header("Content-Type", contentType);
			if (contentLength != null) {
				builder.header(HttpHeaders.CONTENT_LENGTH, contentLength);
			}
			var response = new MockServerHttpResponse();
			handler.handle(builder.body(body), response).block(Duration.ofSeconds(10));
			return response;
		}

		public void close() {
			context.close();
		}

	}

}