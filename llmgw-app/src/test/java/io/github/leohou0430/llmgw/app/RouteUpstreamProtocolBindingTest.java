package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.InMemoryRouteConfigSource;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.RouteTable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T1.10 configuration binding at the application edge: the YAML kebab-case key
 * {@code gateway.routes[i].upstream-protocol} must relaxed-bind onto
 * RouteDef.upstreamProtocol and survive fromProperties + RouteTable.of, so a
 * properties-configured route (not just an admin reload) reaches the conversion
 * dimension. Mirrors the Binder harness in ProductionConfigurationTest.
 */
class RouteUpstreamProtocolBindingTest {

	private GatewayProperties bind(Map<String, Object> overrides) {
		var env = new StandardEnvironment();
		var values = new HashMap<String, Object>();
		values.put("gateway.upstream.base-url", "http://localhost:9090/v1");
		values.putAll(overrides);
		env.getPropertySources().addFirst(new MapPropertySource("test", values));
		return Binder.get(env).bind("gateway", Bindable.of(GatewayProperties.class)).get();
	}

	@Test
	void kebabCaseUpstreamProtocolBindsThroughFromPropertiesAndRouteTable() {
		GatewayProperties properties = bind(Map.of("gateway.routes[0].id", "convert", "gateway.routes[0].model", "m",
				"gateway.routes[0].upstream-protocol", "chat", "gateway.routes[1].id", "plain",
				"gateway.routes[1].model", "m"));
		List<Route> routes = InMemoryRouteConfigSource.fromProperties(properties);
		RouteTable table = RouteTable.of(routes, 1);
		assertEquals("chat", table.byId("convert").upstreamProtocol());
		assertEquals("", table.byId("plain").upstreamProtocol());
	}

	@Test
	void boundChatUpstreamRouteResolvesForAllThreeEntryProtocols() {
		GatewayProperties properties = bind(Map.of("gateway.routes[0].id", "convert", "gateway.routes[0].model", "m",
				"gateway.routes[0].upstream-protocol", "chat"));
		RouteTable table = RouteTable.of(InMemoryRouteConfigSource.fromProperties(properties), 1);
		for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
			assertEquals("chat", table.resolve(kind, "m").upstreamProtocol(), kind.wireName());
		}
	}

}