package io.github.leohou0430.llmgw.core.routing;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.10: the upstreamProtocol routing dimension. "" keeps byte-for-byte passthrough;
 * "chat" marks a route for conversion to the DeepSeek Chat upstream. Pure core contract
 * -- no conversion logic lives here.
 */
class RouteTableTest {

	private static Route route(String id, String protocol, String upstreamProtocol) {
		return new Route(id, protocol, "m", "", "http://localhost:9090/v1", "", "", 1, List.of(), upstreamProtocol);
	}

	private static Route routeWithFallbacks(String id, String upstreamProtocol, List<String> fallbackIds) {
		return new Route(id, "*", "m", "", "http://localhost:9090/v1", "", "", 1, fallbackIds, upstreamProtocol);
	}

	@Test
	void mixedProtocolFallbackChainIsRejected() {
		// The converted upstream payload is built ONCE from the primary route and reused
		// for every
		// fallback, so a fallback whose upstreamProtocol differs would receive a payload
		// in the wrong
		// shape (converted chat bytes sent to a passthrough upstream, or vice versa).
		// Reject both ways.
		Route convert = routeWithFallbacks("convert", "chat", List.of("plain"));
		Route plain = routeWithFallbacks("plain", "", List.of());
		IllegalArgumentException chatToPlain = assertThrows(IllegalArgumentException.class,
				() -> RouteTable.of(List.of(convert, plain), 1));
		assertTrue(chatToPlain.getMessage().contains("convert"), chatToPlain.getMessage());
		assertTrue(chatToPlain.getMessage().contains("plain"), chatToPlain.getMessage());

		Route plainPrimary = routeWithFallbacks("plain2", "", List.of("convert2"));
		Route convertFallback = routeWithFallbacks("convert2", "chat", List.of());
		assertThrows(IllegalArgumentException.class, () -> RouteTable.of(List.of(plainPrimary, convertFallback), 1));
	}

	@Test
	void consistentProtocolFallbackChainIsAccepted() {
		Route a = routeWithFallbacks("a", "chat", List.of("b"));
		Route b = routeWithFallbacks("b", "chat", List.of());
		RouteTable chat = RouteTable.of(List.of(a, b), 1);
		assertEquals("b", chat.fallbacks(chat.byId("a")).get(0).id());

		Route c = routeWithFallbacks("c", "", List.of("d"));
		Route d = routeWithFallbacks("d", "", List.of());
		RouteTable plain = RouteTable.of(List.of(c, d), 1);
		assertEquals("d", plain.fallbacks(plain.byId("c")).get(0).id());
	}

	@Test
	void transitiveFallbackChainMustStayProtocolConsistent() {
		// A(chat) -> B(chat) -> C(chat) is fine; the guard checks every direct edge, so
		// transitivity
		// is covered without a separate graph walk.
		RouteTable.of(List.of(routeWithFallbacks("a", "chat", List.of("b")),
				routeWithFallbacks("b", "chat", List.of("c")), routeWithFallbacks("c", "chat", List.of())), 1);
		// A(chat) -> B(chat) -> C("") breaks at the B->C edge.
		assertThrows(IllegalArgumentException.class,
				() -> RouteTable.of(
						List.of(routeWithFallbacks("a", "chat", List.of("b")),
								routeWithFallbacks("b", "chat", List.of("c")), routeWithFallbacks("c", "", List.of())),
						1));
	}

	@Test
	void unknownOrSelfFallbackIdIsSkippedNotRejected() {
		// fallbacks() silently skips unknown ids and self-references; the consistency
		// guard must not
		// turn either into a hard failure -- only EXISTING, non-self fallback routes are
		// checked.
		Route a = routeWithFallbacks("a", "chat", List.of("ghost", "a"));
		RouteTable table = RouteTable.of(List.of(a), 1);
		assertTrue(table.fallbacks(table.byId("a")).isEmpty());
	}

	@Test
	void chatUpstreamRouteMatchesAllThreeEntryProtocols() {
		RouteTable table = RouteTable.of(List.of(route("convert", "*", "chat")), 1);
		for (ProtocolKind kind : List.of(ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES)) {
			Route resolved = table.resolve(kind, "m");
			assertEquals("convert", resolved.id(), kind.wireName());
			assertEquals("chat", resolved.upstreamProtocol(), kind.wireName());
		}
	}

	@Test
	void unknownUpstreamProtocolIsRejected() {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> RouteTable.of(List.of(route("bad", "*", "bogus")), 1));
		assertTrue(failure.getMessage().contains("bogus"), failure.getMessage());
		assertTrue(failure.getMessage().contains("bad"), failure.getMessage());
	}

	@Test
	void legacyNineArgConstructorDefaultsToPassthrough() {
		Route legacy = new Route("legacy", "*", "m", "", "http://localhost:9090/v1", "", "", 1, List.of());
		assertEquals("", legacy.upstreamProtocol());
		RouteTable table = RouteTable.of(List.of(legacy), 1);
		assertEquals("", table.byId("legacy").upstreamProtocol());
		assertEquals("legacy", table.resolve(ProtocolKind.CHAT, "m").id());
	}

	@Test
	void nullUpstreamProtocolNormalizesToEmptyString() {
		RouteTable table = RouteTable.of(List.of(route("nullish", "*", null)), 1);
		assertEquals("", table.byId("nullish").upstreamProtocol());
		assertEquals("nullish", table.resolve(ProtocolKind.MESSAGES, "m").id());
	}

	@Test
	void protocolNormalizationCarriesUpstreamProtocol() {
		// " chat " trims to "chat" -- a real normalization that forces the
		// reconstruction branch. It must NOT drop upstreamProtocol (the legacy
		// 9-arg ctor would silently reset it to "").
		RouteTable table = RouteTable.of(List.of(route("carry", " chat ", "chat")), 1);
		Route stored = table.byId("carry");
		assertEquals("chat", stored.protocol());
		assertEquals("chat", stored.upstreamProtocol());
		assertEquals("chat", table.resolve(ProtocolKind.RESPONSES, "m").upstreamProtocol());
	}

	@Test
	void blankProtocolIsPreservedVerbatimForByteIdenticalPassthrough() {
		// Legacy of() kept protocol="" as "" (it never rewrote it to "*").
		// Adding the upstreamProtocol dimension must not change that observable
		// value: /admin/routes and Route.protocol() stay byte-identical.
		RouteTable table = RouteTable.of(List.of(route("blank", "", "")), 1);
		assertEquals("", table.byId("blank").protocol());
		assertEquals("", table.byId("blank").upstreamProtocol());
		assertEquals("blank", table.resolve(ProtocolKind.CHAT, "m").id());
	}

	@Test
	void legacyProtocolNormalizationIsByteIdenticalWhenUpstreamEmpty() {
		// Every legacy protocol input must store EXACTLY what of() stored before
		// the upstreamProtocol dimension existed. Expected values are pinned
		// literals from the legacy contract, not recomputed from the impl.
		RouteTable table = RouteTable
			.of(List.of(route("p-null", null, ""), route("p-blank", "", ""), route("p-star", "*", ""),
					route("p-chat", "chat", ""), route("p-spaces", "  ", ""), route("p-padded", " chat ", "")), 1);
		assertEquals("*", table.byId("p-null").protocol());
		assertEquals("", table.byId("p-blank").protocol());
		assertEquals("*", table.byId("p-star").protocol());
		assertEquals("chat", table.byId("p-chat").protocol());
		assertEquals("*", table.byId("p-spaces").protocol());
		assertEquals("chat", table.byId("p-padded").protocol());
		for (String id : List.of("p-null", "p-blank", "p-star", "p-chat", "p-spaces", "p-padded")) {
			assertEquals("", table.byId(id).upstreamProtocol(), id);
		}
	}

	@Test
	void blankProtocolWithChatUpstreamIsPreservedAndCarried() {
		// The exact combination the first implementation broke: protocol="" AND
		// upstreamProtocol="chat". The keep-candidate branch must preserve ""
		// verbatim while carrying "chat"; byId and the route list share one object.
		RouteTable table = RouteTable.of(List.of(route("blank-chat", "", "chat")), 1);
		Route stored = table.byId("blank-chat");
		assertEquals("", stored.protocol());
		assertEquals("chat", stored.upstreamProtocol());
		assertSame(stored, table.routes().get(0));
	}

	@Test
	void nullProtocolAndPaddedUpstreamNormalizeAndCarry() {
		RouteTable nullProto = RouteTable.of(List.of(route("np", null, "chat")), 1);
		assertEquals("*", nullProto.byId("np").protocol());
		assertEquals("chat", nullProto.byId("np").upstreamProtocol());
		RouteTable padded = RouteTable.of(List.of(route("pad", "*", " chat ")), 1);
		assertEquals("chat", padded.byId("pad").upstreamProtocol());
	}

	@Test
	void fromPropertiesBindsUpstreamProtocol() {
		GatewayProperties properties = new GatewayProperties();
		properties.getUpstream().setBaseUrl("http://localhost:9090/v1");
		GatewayProperties.RouteDef converting = new GatewayProperties.RouteDef();
		converting.setId("convert");
		converting.setModel("m");
		converting.setUpstreamProtocol("chat");
		GatewayProperties.RouteDef passthrough = new GatewayProperties.RouteDef();
		passthrough.setId("plain");
		passthrough.setModel("m");
		properties.getRoutes().add(converting);
		properties.getRoutes().add(passthrough);

		List<Route> routes = InMemoryRouteConfigSource.fromProperties(properties);
		assertEquals(2, routes.size());
		Route convert = routes.stream().filter(r -> r.id().equals("convert")).findFirst().orElseThrow();
		Route plain = routes.stream().filter(r -> r.id().equals("plain")).findFirst().orElseThrow();
		assertEquals("chat", convert.upstreamProtocol());
		assertEquals("", plain.upstreamProtocol());
	}

}