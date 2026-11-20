package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.engine.BillableAttempt;
import io.github.leohou0430.llmgw.engine.BillableCall;
import io.github.leohou0430.llmgw.engine.ForwardingPolicy;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamDisconnectTest {

	@Test
	void heartbeatDisconnectDoesNotPoisonUpstreamOrTryFallback() throws Exception {
		verify(": keep-alive\n\n", false);
	}

	@Test
	void firstDataWriteDisconnectDoesNotPoisonUpstreamOrTryFallback() throws Exception {
		verify("data: {}\n", false);
	}

	@Test
	void upstreamFailureStillRecordsFailureAndTriesFallback() throws Exception {
		verify(null, true);
	}

	private void verify(String chunk, boolean upstreamFailure) throws Exception {
		int[] failures = { 0 }, calls = { 0 };
		var breaker = new CircuitBreaker() {
			public boolean allow(Route route) {
				return true;
			}

			public void recordSuccess(Route route) {
				fail("broken stream cannot complete normally");
			}

			public void recordFailure(Route route) {
				failures[0]++;
			}
		};
		var provider = (UpstreamProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { UpstreamProvider.class }, (proxy, method, args) -> {
					if (!method.getName().equals("streamRawBlocking"))
						throw new AssertionError(method);
					calls[0]++;
					if (upstreamFailure)
						throw new IllegalStateException(new java.net.ConnectException("upstream refused"));
					((ChunkSink) args[1]).onChunk(chunk);
					return null;
				});
		var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
					if (method.getName().equals("getOutputStream"))
						throw new IOException("client closed");
					throw new AssertionError(method);
				});
		var controller = new VirtualThreadGatewayController(null, null, provider, breaker, new GatewayProperties(),
				null, null);
		var sinkType = Class.forName(VirtualThreadGatewayController.class.getName() + "$StreamSink");
		var ctor = sinkType.getDeclaredConstructor(HttpServletResponse.class, GatewayContext.class, HeaderPolicy.class);
		ctor.setAccessible(true);
		var context = new GatewayContext("key", "model", "primary", true, 1);
		var sink = ctor.newInstance(response, context, null);
		var method = VirtualThreadGatewayController.class.getDeclaredMethod("streamWithPlan", List.class, int.class,
				String.class, ParsedRequest.class, int.class, sinkType, GatewayContext.class, Map.class, long.class,
				PreparedConversion.class, BillableCall.class, BillableAttempt[].class,
				ForwardingPolicy.AttemptBudget.class);
		method.setAccessible(true);
		var primary = new Route("primary", "model", "model", "http://localhost/v1", "", 1, List.of());
		var fallback = new Route("fallback", "model", "model", "http://localhost/v1", "", 1, List.of());
		var plan = List.of(new ForwardingPolicy.Attempt(primary, 1, 0), new ForwardingPolicy.Attempt(fallback, 1, 0));
		var raw = "{\"model\":\"model\",\"stream\":true,\"messages\":[]}";
		// §9.2.4 wiring: no billing pack on the classpath → no-op call; unlimited budget.
		var billable = BillableCall.open(null, null, null, new BillingInput("chat", 1, 1, 1, 0), "t", "p", "m");
		var lastAttempt = new BillableAttempt[1];
		assertThrows(InvocationTargetException.class,
				() -> method.invoke(controller, plan, 0, raw, ProtocolCodec.parse(raw), 1000, sink, context, Map.of(),
						0L, null, billable, lastAttempt, ForwardingPolicy.AttemptBudget.unlimited()));
		assertEquals(upstreamFailure ? 2 : 0, failures[0]);
		assertEquals(upstreamFailure ? 2 : 1, calls[0]);
	}

}
