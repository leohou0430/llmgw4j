package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.11 named-module assembly seams. The graph is composed with the native
 * ExtensionContext only: no Spring context, no bean scanning, no implementation class
 * referenced by name. The codec impls reach this test through llmgw-app's runtime
 * classpath, exactly as they do in production.
 */
class ConversionModuleTest {

	private static GatewayProperties properties() {
		return new GatewayProperties();
	}

	private static ExtensionContext conversionContext(GatewayProperties properties) {
		var context = new ExtensionContext();
		context.external(GatewayProperties.class, properties);
		context.loadModule("conversion");
		return context;
	}

	private static Route route(String upstreamProtocol) {
		return new Route("r", "chat", "client-model", "deepseek-chat", "https://api.deepseek.com", "sk-test", "", 1,
				List.of(), upstreamProtocol);
	}

	@Test
	void namedModuleIsDiscoveredAndBindsTheConversionService() {
		try (var context = conversionContext(properties())) {
			context.seal();
			ConversionService service = context.get(ConversionService.class);
			assertTrue(service != null, "conversion module must bind ConversionService");
			assertSame(service, context.get(ConversionService.class));
			assertTrue(context.get(ContinuationEnvelope.class) != null);
		}
	}

	@Test
	void appliesOnlyWhenTheRouteAsksForAnUpstreamProtocol() {
		try (var context = conversionContext(properties())) {
			context.seal();
			ConversionService service = context.get(ConversionService.class);
			assertFalse(service.applies(route("")), "blank upstreamProtocol is byte-for-byte passthrough");
			assertFalse(service.applies(route("   ")));
			assertFalse(service.applies(null));
			assertTrue(service.applies(route("chat")));
			// The legacy 9-component constructor keeps the passthrough default.
			assertFalse(service.applies(new Route("r", "chat", "m", "t", "http://localhost", "", 1, List.of())));
		}
	}

	@Test
	void replacementBeforeSealIsUsedAndAfterSealFails() {
		ConversionService replacement = new ConversionService() {
			@Override
			public boolean applies(Route route) {
				return false;
			}

			@Override
			public io.github.leohou0430.llmgw.conversion.PreparedConversion prepare(
					io.github.leohou0430.llmgw.core.protocol.ParsedRequest parsed, Route route, String tenantId,
					String projectId, io.github.leohou0430.llmgw.conversion.DegradationGrant grant) {
				throw new UnsupportedOperationException("not used by this test");
			}
		};
		try (var context = conversionContext(properties())) {
			context.replace(ConversionService.class, () -> replacement);
			context.seal();
			assertSame(replacement, context.get(ConversionService.class));
			assertThrows(IllegalStateException.class,
					() -> context.replace(ConversionService.class, () -> replacement));
		}
	}

	@Test
	void unknownModuleNameFails() {
		try (var context = new ExtensionContext()) {
			context.external(GatewayProperties.class, properties());
			assertThrows(IllegalStateException.class, () -> context.loadModule("nope"));
		}
	}

	private static final String ENTRY_DESCRIPTOR = "META-INF/llmgw/extensions/" + EntryCodec.class.getName();

	@Test
	void zeroDiscoveredEntryCodecsFailsFast() {
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		// Hide only the EntryCodec descriptor; the module and upstream descriptors still
		// resolve,
		// so the failure is specifically "no entry codec", not a missing module.
		ClassLoader hiding = new ClassLoader(original) {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				return name.equals(ENTRY_DESCRIPTOR) ? Collections.emptyEnumeration() : super.getResources(name);
			}
		};
		Thread.currentThread().setContextClassLoader(hiding);
		try (var context = new ExtensionContext()) {
			context.external(GatewayProperties.class, properties());
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> context.loadModule("conversion"));
			assertTrue(failure.getMessage().contains("no entry codec discovered"), failure.getMessage());
		}
		finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	@Test
	void duplicateEntryCodecKindFailsFast() throws IOException {
		// A second alias for the SAME chat codec class makes two discovered names claim
		// protocol
		// CHAT; the assembly must reject the conflict rather than silently pick one.
		Path dir = Files.createTempDirectory("llmgw-dup-codec");
		Path descriptor = dir.resolve(ENTRY_DESCRIPTOR);
		Files.createDirectories(descriptor.getParent());
		Files.writeString(descriptor, "chat-dup=io.github.leohou0430.llmgw.conversion.chat.ChatEntryCodec\n");
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try (var extra = new URLClassLoader(new URL[] { dir.toUri().toURL() }, original)) {
			Thread.currentThread().setContextClassLoader(extra);
			try (var context = new ExtensionContext()) {
				context.external(GatewayProperties.class, properties());
				IllegalStateException failure = assertThrows(IllegalStateException.class,
						() -> context.loadModule("conversion"));
				assertTrue(failure.getMessage().contains("duplicate entry codec for protocol"), failure.getMessage());
			}
			finally {
				Thread.currentThread().setContextClassLoader(original);
			}
		}
		finally {
			deleteRecursively(dir);
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	@Test
	void twoContextsOwnSeparateServicesAndEnvelopes() {
		try (var a = conversionContext(properties()); var b = conversionContext(properties())) {
			a.seal();
			b.seal();
			assertNotSame(a.get(ConversionService.class), b.get(ConversionService.class));
			ContinuationEnvelope envelopeA = a.get(ContinuationEnvelope.class);
			ContinuationEnvelope envelopeB = b.get(ContinuationEnvelope.class);
			assertNotSame(envelopeA, envelopeB);
			// A blank configured key is process/context scoped: a token minted in one
			// runtime must
			// not verify in another, proving no static shared key or envelope state.
			String token = envelopeA.mint("project-1", "item-1", "reasoning");
			envelopeA.verify(token, "project-1", "reasoning");
			assertThrows(CapabilityException.class, () -> envelopeB.verify(token, "project-1", "reasoning"));
		}
	}

	@Test
	void configuredContinuationKeyIsTheSingleSourceOfTruth() {
		var first = properties();
		first.getConversion().setContinuationKey("stable-secret-from-env");
		var second = properties();
		second.getConversion().setContinuationKey("stable-secret-from-env");
		try (var a = conversionContext(first); var b = conversionContext(second)) {
			a.seal();
			b.seal();
			String token = a.get(ContinuationEnvelope.class).mint("project-1", "item-1", "reasoning");
			b.get(ContinuationEnvelope.class).verify(token, "project-1", "reasoning");
		}
	}

	@Test
	void ownedResourcesCloseInReverseOrderOnceAndCloseIsIdempotent() {
		var events = new ArrayList<String>();
		class First implements AutoCloseable {

			@Override
			public void close() {
				events.add("first");
			}

		}
		class Last implements AutoCloseable {

			@Override
			public void close() {
				events.add("last");
			}

		}
		var context = conversionContext(properties());
		context.bind(First.class, First::new);
		context.bind(Last.class, Last::new);
		context.seal();
		context.get(First.class);
		context.get(ConversionService.class);
		context.get(Last.class);
		context.close();
		context.close();
		assertEquals(List.of("last", "first"), events);
		assertThrows(IllegalStateException.class, () -> context.get(ConversionService.class));
	}

}