package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.routing.InMemoryRouteConfigSource;
import io.github.leohou0430.llmgw.core.routing.RouteConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T1.10 admin surface: POST /admin/routes/reload accepts an optional upstreamProtocol (""
 * passthrough | "chat" convert-to-chat), rejects unknown values with 400, and echoes the
 * normalized value in the route view. Uses the same null-collaborator harness as
 * AdminAuthorizationTest; only a real route source and properties are needed for reload.
 */
class AdminRouteUpstreamProtocolTest {

	private AdminController controller() {
		var properties = new GatewayProperties();
		// Blank admin tokens = open local demo, so requireAdmin(null) passes.
		properties.getUpstream().setBaseUrl("http://localhost");
		return controllerWith(new InMemoryRouteConfigSource(properties), properties);
	}

	private AdminController controllerWith(InMemoryRouteConfigSource source, GatewayProperties properties) {
		var beans = new DefaultListableBeanFactory();
		beans.registerSingleton("routeSource", source);
		ObjectProvider<RouteConfigSource> routeSources = beans.getBeanProvider(RouteConfigSource.class);
		return new AdminController(null, null, null, null, null, null, null, null, null, null, null, routeSources,
				properties);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> item(Map<String, Object> out, String id) {
		var routes = (List<Map<String, Object>>) out.get("routes");
		return routes.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
	}

	private static AdminController.RouteSpec spec(String id, String upstreamProtocol) {
		return new AdminController.RouteSpec(id, "", "m", "", null, null, "", 1, List.of(), upstreamProtocol);
	}

	@Test
	void reloadWithChatUpstreamProtocolIsAppliedAndEchoed() {
		var out = controller().reloadRoutes(null, List.of(spec("r1", "chat")));
		assertEquals(true, out.get("applied"));
		assertEquals("chat", item(out, "r1").get("upstreamProtocol"));
	}

	@Test
	void reloadWithUnknownUpstreamProtocolIsRejectedWith400AndLeavesTableUntouched() {
		var properties = new GatewayProperties();
		properties.getUpstream().setBaseUrl("http://localhost");
		var source = new InMemoryRouteConfigSource(properties);
		long versionBefore = source.current().version();
		var admin = controllerWith(source, properties);
		var failure = assertThrows(ResponseStatusException.class,
				() -> admin.reloadRoutes(null, List.of(spec("r2", "bogus"))));
		assertEquals(400, failure.getStatusCode().value());
		assertEquals(versionBefore, source.current().version(), "rejected reload must not swap the table");
		assertNull(source.current().byId("r2"), "rejected route must not be inserted");
	}

	@Test
	void reloadWithoutUpstreamProtocolDefaultsToPassthrough() {
		var out = controller().reloadRoutes(null, List.of(spec("r3", null)));
		assertEquals(true, out.get("applied"));
		assertEquals("", item(out, "r3").get("upstreamProtocol"));
	}

}