package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.4 seam: the VT Files upload must stream the client multipart body through the
 * scanner+spool and forward it byte-for-byte as a cold {@link ByteStream} (never a whole
 * byte[]), bind the returned file id, map limit violations to 413, and release the
 * request permit + spool on reject/error so a bounded pool is not leaked. Drives the real
 * {@code handleFileUpload → handleUpload} dispatch with Proxy servlet stubs, mirroring
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
	void streamsUploadByteFaithfulAndBindsResourceId() throws Exception {
		byte[] file = pattern(40000);
		byte[] body = multipart(file);
		var h = new Harness(4 << 20, 10, null, false);
		var resp = h.response();
		h.controller.handleFileUpload(h.request(body, "multipart/form-data; boundary=" + B), resp.proxy);
		assertEquals(200, resp.status[0]);
		assertEquals(1, h.provider.rawCalls.get(), "upload must POST via fetchRaw exactly once");
		assertArrayEquals(body, h.provider.capturedBody, "upstream body is byte-for-byte the client multipart");
		assertEquals(body.length, h.provider.capturedLength, "Content-Length is the exact spooled byte count");
		assertTrue(h.provider.chunks.get() >= 2, "body must be forwarded in multiple bounded chunks, not one buffer");
		assertTrue(resp.body.toString(StandardCharsets.UTF_8).contains("\"id\":\"f\""));
		assertEquals("test", h.tasks.resolve("tenant", "files", "f"), "returned file id is sticky-bound to the route");
		h.controller.close();
	}

	@Test
	void oversizedMultipartIs413AndSkipsUpstream() throws Exception {
		byte[] body = multipart(pattern(40000));
		var h = new Harness(100, 10, null, false); // whole-multipart bound = 100 bytes
		var resp = h.response();
		h.controller.handleFileUpload(h.request(body, "multipart/form-data; boundary=" + B), resp.proxy);
		assertEquals(413, resp.status[0]);
		assertEquals(0, h.provider.rawCalls.get(), "an over-limit body must never reach the upstream");
		h.controller.close();
	}

	@Test
	void missingBoundaryIs400() throws Exception {
		var h = new Harness(4 << 20, 10, null, false);
		var resp = h.response();
		h.controller.handleFileUpload(h.request(multipart(pattern(10)), "multipart/form-data"), resp.proxy);
		assertEquals(400, resp.status[0]);
		assertEquals(0, h.provider.rawCalls.get());
		h.controller.close();
	}

	@Test
	void governanceRejectReleasesPermit() throws Exception {
		// Pool of ONE: if the reject path leaked the permit, the next request 503s.
		var h = new Harness(4 << 20, 1, GatewayDecision.deny(403, "denied"), false);
		var reject = h.response();
		h.controller.handleFileUpload(h.request(multipart(pattern(10)), "multipart/form-data; boundary=" + B),
				reject.proxy);
		assertEquals(403, reject.status[0]);
		assertEquals(0, h.provider.rawCalls.get());

		h.decision = null; // now allow
		var ok = h.response();
		h.controller.handleFileUpload(h.request(multipart(pattern(10)), "multipart/form-data; boundary=" + B),
				ok.proxy);
		assertEquals(200, ok.status[0], "permit must be released after a governance reject");
		h.controller.close();
	}

	@Test
	void upstreamErrorIs502AndReleasesPermit() throws Exception {
		var h = new Harness(4 << 20, 1, null, true); // provider throws
		var fail = h.response();
		h.controller.handleFileUpload(h.request(multipart(pattern(10)), "multipart/form-data; boundary=" + B),
				fail.proxy);
		assertEquals(502, fail.status[0]);

		h.provider.failUpstream = false; // now succeed
		var ok = h.response();
		h.controller.handleFileUpload(h.request(multipart(pattern(10)), "multipart/form-data; boundary=" + B),
				ok.proxy);
		assertEquals(200, ok.status[0], "permit must be released after an upstream error");
		h.controller.close();
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

	static final class Resp {

		final int[] status = { 0 };

		final ByteArrayOutputStream body = new ByteArrayOutputStream();

		volatile boolean committed;

		final HttpServletResponse proxy;

		Resp() {
			var out = new ServletOutputStream() {
				public void write(int b) {
					committed = true;
					body.write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) {
					committed = true;
					body.write(b, off, len);
				}

				public boolean isReady() {
					return true;
				}

				public void setWriteListener(WriteListener l) {
				}
			};
			proxy = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletResponse.class }, (p, m, a) -> switch (m.getName()) {
						case "setStatus" -> {
							status[0] = (Integer) a[0];
							yield null;
						}
						case "getStatus" -> status[0];
						case "setContentType" -> null;
						case "getOutputStream" -> out;
						case "isCommitted" -> committed;
						default -> defaultValue(m.getReturnType());
					});
		}

	}

	static final class Harness {

		final VirtualThreadGatewayController controller;

		final CapturingProvider provider;

		final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);

		volatile GatewayDecision decision;

		Harness(long maxRequestBytes, int maxConcurrent, GatewayDecision initialDecision, boolean failUpstream) {
			this.decision = initialDecision;
			provider = new CapturingProvider(failUpstream);
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
				public GatewayDecision enter(String token, ParsedRequest req, Route target) {
					return decision != null ? decision : GatewayDecision.allow("tenant", req, target);
				}

				public Optional<String> lookupCache(String key, ParsedRequest req) {
					return Optional.empty();
				}

				public void cache(String key, ParsedRequest req, String responseBody) {
				}

				public void finish(GatewayContext context, boolean success) {
				}

				public Map<String, Object> snapshot() {
					return Map.of();
				}
			};
			var headerPolicy = new HeaderPolicy() {
				public Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> values) {
					return Map.of();
				}

				public List<String> toClient(Map<String, List<String>> values) {
					return List.of();
				}
			};
			var properties = new GatewayProperties();
			properties.setMaxConcurrentRequests(maxConcurrent);
			properties.setMaxRequestBytes(maxRequestBytes);
			controller = new VirtualThreadGatewayController(chain, router, provider, CircuitBreaker.noop(), properties,
					headerPolicy, tasks);
		}

		Resp response() {
			return new Resp();
		}

		HttpServletRequest request(byte[] body, String contentType) {
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Authorization", "Bearer test-token");
			headers.put("Content-Type", contentType);
			var in = new ServletInputStream() {
				final ByteArrayInputStream src = new ByteArrayInputStream(body);

				public int read() {
					return src.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return src.read(b, off, len);
				}

				public boolean isFinished() {
					return src.available() == 0;
				}

				public boolean isReady() {
					return true;
				}

				public void setReadListener(ReadListener l) {
				}
			};
			return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> switch (m.getName()) {
						case "getInputStream" -> in;
						case "getContentType" -> contentType;
						case "getHeader" -> headers.get((String) a[0]);
						case "getHeaderNames" -> Collections.enumeration(headers.keySet());
						default -> defaultValue(m.getReturnType());
					});
		}

	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive())
			return null;
		if (type == boolean.class)
			return false;
		if (type == int.class)
			return 0;
		if (type == long.class)
			return 0L;
		if (type == short.class)
			return (short) 0;
		if (type == byte.class)
			return (byte) 0;
		if (type == char.class)
			return (char) 0;
		if (type == float.class)
			return 0f;
		if (type == double.class)
			return 0d;
		return null;
	}

}