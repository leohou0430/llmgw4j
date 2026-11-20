package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MultipartHeaderPolicyTest {

	@Test
	void uploadsPreserveTheBoundaryEvenWithAnEmptyOptionalAllowlist() {
		var properties = new GatewayProperties();
		properties.getHeaders().setUpstreamAllow(List.of());
		var policy = new AllowListHeaderPolicy(properties);
		var headers = Map.of("Content-Type", "multipart/form-data; boundary=SdkBoundary", "Authorization",
				"Bearer caller", "Host", "foreign", "Content-Length", "999", "Cookie", "secret");
		for (var kind : List.of(ProtocolKind.FILES, ProtocolKind.TRANSCRIPTIONS)) {
			assertEquals(Map.of("content-type", "multipart/form-data; boundary=SdkBoundary"),
					policy.toUpstream(kind, headers));
		}
	}

	@Test
	void jsonAndResponseMediaTypesRemainGatewayOwned() {
		var properties = new GatewayProperties();
		properties.getHeaders().setUpstreamAllow(List.of("*"));
		properties.getHeaders().setClientAllow(List.of("*"));
		var policy = new AllowListHeaderPolicy(properties);
		for (var kind : ProtocolKind.values()) {
			var forwarded = policy.toUpstream(kind, Map.of("content-type", "multipart/form-data; boundary=B",
					"authorization", "Bearer caller", "x-api-key", "caller"));
			assertFalse(forwarded.containsKey("authorization"));
			// UpstreamRequest also strips x-api-key before provider credential injection.
			assertEquals(kind == ProtocolKind.FILES || kind == ProtocolKind.TRANSCRIPTIONS,
					forwarded.containsKey("content-type"));
		}
		assertTrue(policy.toClient(Map.of("Content-Type", List.of("text/plain"))).isEmpty());
	}

	@Test
	void gatewayOwnedNamespaceIsStrippedEvenWithWildcardAllowlist() {
		var properties = new GatewayProperties();
		properties.getHeaders().setUpstreamAllow(List.of("*"));
		var policy = new AllowListHeaderPolicy(properties);
		var headers = Map.of("X-LLMGW-Overhead-Ms", "123", "x-llmgw-no-cache", "1", "X-LLMGW-Allow-Degradation",
				"thinking.display_raw", "x-llmgw-upstream-ms", "456", "x-custom", "keep");
		for (var kind : ProtocolKind.values()) {
			var forwarded = policy.toUpstream(kind, headers);
			assertTrue(forwarded.keySet().stream().noneMatch(n -> n.startsWith("x-llmgw-")),
					"gateway-owned x-llmgw-* must never forward upstream: " + kind);
			assertEquals("keep", forwarded.get("x-custom"), "non-gateway headers still pass under wildcard: " + kind);
		}
	}

}
