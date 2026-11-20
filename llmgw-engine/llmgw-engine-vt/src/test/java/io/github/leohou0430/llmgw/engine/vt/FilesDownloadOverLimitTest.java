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
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.9 seam: a download that exceeds the upstream byte cap MID-STREAM (after the first
 * chunk has committed the 200 response) must be reported as a failure — partial bytes
 * delivered, governance finish recorded as failure and the circuit breaker told — never a
 * silent clean success. The upstream may already have sent part of the body before the
 * limit is discovered; the gateway does not pretend the transfer completed.
 */
class FilesDownloadOverLimitTest {

	private static byte[] pattern(int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i % 251);
		}
		return b;
	}

	@Test
	void overLimitMidStreamAfterCommitIsFailureNotSilentSuccess() throws Exception {
		// Two chunks reach the client (committing the 200), then the upstream
		// errors as the cumulative byte cap is discovered.
		ByteStream.Source source = new ByteStream.Source() {
			private int n;

			@Override
			public ByteChunk next() throws IOException {
				if (n++ >= 2) {
					throw new IOException("upstream response exceeds maxResponseBytes");
				}
				return new ByteChunk(ByteBuffer.wrap(pattern(16)));
			}

			@Override
			public void close() {
			}
		};
		var h = new Harness(ByteStream.of(source));
		h.tasks.bind("tenant", "files", "f", "test");
		h.controller.handleFileContent("f", h.request, h.response);
		assertEquals(200, h.status[0], "headers commit before the first byte");
		assertTrue(h.committed, "partial body must have been delivered");
		byte[] expected = new byte[32];
		System.arraycopy(pattern(16), 0, expected, 0, 16);
		System.arraycopy(pattern(16), 0, expected, 16, 16);
		assertArrayEquals(expected, h.body.toByteArray(), "exactly the pre-limit chunks reach the client");
		assertFalse(h.finishSuccess.get(), "a mid-stream over-limit must finish as failure, not silent success");
	}

	static final class Harness {

		final int[] status = { 0 };

		final ByteArrayOutputStream body = new ByteArrayOutputStream();

		final AtomicBoolean finishSuccess = new AtomicBoolean(true);

		final InMemoryTaskRouteSticky tasks = new InMemoryTaskRouteSticky(10, 60);

		final VirtualThreadGatewayController controller;

		final HttpServletRequest request;

		final HttpServletResponse response;

		volatile boolean committed;

		Harness(ByteStream download) {
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Authorization", "Bearer test-token");
			var servletIn = new ServletInputStream() {
				final ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);

				public int read() {
					return in.read();
				}

				public boolean isFinished() {
					return in.available() == 0;
				}

				public boolean isReady() {
					return true;
				}

				public void setReadListener(ReadListener listener) {
				}
			};
			request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> switch (method.getName()) {
						case "getInputStream" -> servletIn;
						case "getHeader" -> headers.get((String) args[0]);
						case "getHeaderNames" -> Collections.enumeration(headers.keySet());
						default -> defaultValue(method.getReturnType());
					});
			var servletOut = new ServletOutputStream() {
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

				public void setWriteListener(WriteListener listener) {
				}
			};
			response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> switch (method.getName()) {
						case "setStatus" -> {
							status[0] = (Integer) args[0];
							yield null;
						}
						case "getStatus" -> status[0];
						case "setContentType" -> null;
						case "setHeader" -> null;
						case "getOutputStream" -> servletOut;
						case "isCommitted" -> committed;
						case "flushBuffer" -> null;
						default -> defaultValue(method.getReturnType());
					});
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
					return GatewayDecision.allow("tenant", req, target);
				}

				public Optional<String> lookupCache(String key, ParsedRequest req) {
					return Optional.empty();
				}

				public void cache(String key, ParsedRequest req, String responseBody) {
				}

				public void finish(GatewayContext context, boolean success) {
					finishSuccess.set(success);
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
			var provider = new UpstreamProvider() {
				public String id() {
					return "stream";
				}

				public Flow.Publisher<String> streamRaw(UpstreamRequest req, HeaderListener listener) {
					throw new UnsupportedOperationException();
				}

				public void streamRawBlocking(UpstreamRequest req, ChunkSink sink) {
					throw new UnsupportedOperationException();
				}

				public String completeRaw(UpstreamRequest req, HeaderListener listener) {
					throw new UnsupportedOperationException();
				}

				@Override
				public byte[] fetchRaw(UpstreamRequest req) {
					throw new UnsupportedOperationException("content must stream");
				}

				@Override
				public Flow.Publisher<ByteBuffer> fetchStream(UpstreamRequest req) {
					return download;
				}
			};
			var properties = new GatewayProperties();
			properties.setMaxConcurrentRequests(10);
			controller = new VirtualThreadGatewayController(chain, router, provider, CircuitBreaker.noop(), properties,
					headerPolicy, tasks);
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