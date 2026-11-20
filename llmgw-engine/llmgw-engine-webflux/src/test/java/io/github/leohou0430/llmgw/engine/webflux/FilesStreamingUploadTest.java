package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.4 seam: the WebFlux Files upload must stream the client multipart body through the
 * scanner+spool (never {@code DataBufferUtils.join} of the whole body), forward it
 * byte-for-byte as a cold {@link ByteStream}, bind the returned file id, map limit
 * violations to 413 and a missing boundary to 400, and render upstream failures as 502.
 * Drives the real controller through the reactive HttpHandler seam, mirroring
 * FilesStreamingDownloadTest.
 */
class FilesStreamingUploadTest {

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
	void streamsUploadByteFaithfulAndBindsResourceId() {
		byte[] body = multipart(pattern(40000));
		try (var fixture = new Fixture(4 << 20, null, false)) {
			var response = fixture.post(body, "multipart/form-data; boundary=" + B);
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertEquals(1, fixture.provider.rawCalls.get(), "upload must POST via fetchRaw exactly once");
			assertArrayEquals(body, fixture.provider.capturedBody,
					"upstream body is byte-for-byte the client multipart");
			assertEquals(body.length, fixture.provider.capturedLength,
					"Content-Length is the exact spooled byte count");
			assertTrue(fixture.provider.chunks.get() >= 2,
					"body must be forwarded in multiple bounded chunks, not one buffer");
			assertTrue(response.getBodyAsString().block(Duration.ofSeconds(5)).contains("\"id\":\"f\""));
			assertEquals("test", fixture.tasks.resolve("tenant", "files", "f"),
					"returned file id is sticky-bound to the route");
		}
	}

	@Test
	void oversizedMultipartIs413AndSkipsUpstream() {
		byte[] body = multipart(pattern(40000));
		try (var fixture = new Fixture(100, null, false)) { // whole-multipart bound = 100
															// bytes
			var response = fixture.post(body, "multipart/form-data; boundary=" + B);
			assertEquals(413, response.getStatusCode().value());
			assertEquals(0, fixture.provider.rawCalls.get(), "an over-limit body must never reach the upstream");
		}
	}

	@Test
	void missingBoundaryIs400() {
		try (var fixture = new Fixture(4 << 20, null, false)) {
			var response = fixture.post(multipart(pattern(10)), "multipart/form-data");
			assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
			assertEquals(0, fixture.provider.rawCalls.get());
		}
	}

	@Test
	void upstreamErrorIs502() {
		try (var fixture = new Fixture(4 << 20, null, true)) {
			var response = fixture.post(multipart(pattern(10)), "multipart/form-data; boundary=" + B);
			assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
			assertTrue(response.getBodyAsString().block(Duration.ofSeconds(5)).contains("error"));
		}
	}

	@Test
	void governanceRejectIs403AndSkipsUpstream() {
		try (var fixture = new Fixture(4 << 20, GatewayDecision.deny(403, "denied"), false)) {
			var response = fixture.post(multipart(pattern(10)), "multipart/form-data; boundary=" + B);
			assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
			assertEquals(0, fixture.provider.rawCalls.get());
		}
	}

	/**
	 * Streaming proof: with a whole-body join every client DataBuffer stays alive until
	 * the last one arrives; with the bounded spool bridge each buffer is released before
	 * the next is requested, so peak live buffers stay small.
	 */
	@Test
	void clientBuffersAreReleasedIncrementallyNotRetainedWhole() {
		byte[] body = multipart(pattern(40000));
		var alloc = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
		List<ByteBuf> emitted = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger maxLive = new AtomicInteger();
		int slice = 512;
		List<byte[]> slices = new ArrayList<>();
		for (int off = 0; off < body.length; off += slice) {
			slices.add(Arrays.copyOfRange(body, off, Math.min(body.length, off + slice)));
		}
		Flux<DataBuffer> clientBody = Flux.fromIterable(slices).map(bytes -> {
			var buffer = (org.springframework.core.io.buffer.NettyDataBuffer) alloc.wrap(bytes);
			emitted.add(buffer.getNativeBuffer());
			return (DataBuffer) buffer;
		}).doOnNext(buffer -> {
			int live = 0;
			for (ByteBuf b : emitted) {
				if (b.refCnt() > 0) {
					live++;
				}
			}
			maxLive.accumulateAndGet(live, Math::max);
		});
		try (var fixture = new Fixture(4 << 20, null, false)) {
			var response = fixture.post(clientBody, "multipart/form-data; boundary=" + B);
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertTrue(maxLive.get() <= 8, "peak live client buffers must stay bounded, was " + maxLive.get()
					+ " (whole-body join retains all)");
			for (ByteBuf b : emitted) {
				assertEquals(0, b.refCnt(), "every client buffer must be released exactly once");
			}
		}
	}

	/**
	 * Captures the forwarded body by draining the cold ByteStream on the calling thread.
	 */
	static final class CapturingProvider implements UpstreamProvider {

		final AtomicInteger rawCalls = new AtomicInteger();

		volatile byte[] capturedBody = new byte[0];

		volatile long capturedLength;

		final AtomicInteger chunks = new AtomicInteger();

		volatile boolean failUpstream;

		CapturingProvider(boolean failUpstream) {
			this.failUpstream = failUpstream;
		}

		@Override
		public String id() {
			return "upload";
		}

		@Override
		public Flow.Publisher<String> streamRaw(UpstreamRequest req, HeaderListener l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void streamRawBlocking(UpstreamRequest req, ChunkSink sink) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String completeRaw(UpstreamRequest req, HeaderListener l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest req) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] fetchRaw(UpstreamRequest req) {
			rawCalls.incrementAndGet();
			capturedLength = req.bodyLength();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			CountDownLatch done = new CountDownLatch(1);
			req.body().subscribe(new Flow.Subscriber<>() {
				Flow.Subscription s;

				@Override
				public void onSubscribe(Flow.Subscription s) {
					this.s = s;
					s.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(ByteBuffer b) {
					chunks.incrementAndGet();
					byte[] t = new byte[b.remaining()];
					b.get(t);
					out.writeBytes(t);
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
				assertTrue(done.await(5, TimeUnit.SECONDS), "body stream completes");
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			capturedBody = out.toByteArray();
			if (failUpstream) {
				throw new RuntimeException("upstream gone");
			}
			return "{\"id\":\"f\",\"object\":\"file\"}".getBytes(StandardCharsets.UTF_8);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class WebConfig extends WebFluxConfigurationSupport {

	}

	static class Fixture implements AutoCloseable {

		final CapturingProvider provider;

		final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);

		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

		final HttpHandler handler;

		Fixture(long maxRequestBytes, GatewayDecision decision, boolean failUpstream) {
			provider = new CapturingProvider(failUpstream);
			var properties = new GatewayProperties();
			properties.setMaxConcurrentRequests(10);
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
					return decision != null ? decision : GatewayDecision.allow("tenant", request, target);
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

		MockServerHttpResponse post(byte[] body, String contentType) {
			var alloc = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
			return post(Flux.just(alloc.wrap(body)), contentType);
		}

		MockServerHttpResponse post(Flux<DataBuffer> body, String contentType) {
			var response = new MockServerHttpResponse();
			handler
				.handle(MockServerHttpRequest.post("/v1/files")
					.header("Authorization", "Bearer test-token")
					.header("Content-Type", contentType)
					.body(body), response)
				.block(Duration.ofSeconds(10));
			return response;
		}

		public void close() {
			context.close();
		}

	}

}