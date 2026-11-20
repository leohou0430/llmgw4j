package io.github.leohou0430.llmgw.conversion.chat;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCodecDiscoveryTest {

	@Test
	void namedDiscoveryFindsChatEntryCodec() {
		Extensions<EntryCodec> extensions = Extensions.load(EntryCodec.class);
		assertTrue(extensions.hasExtension("chat"));
		EntryCodec codec = extensions.getExtension("chat");
		assertInstanceOf(ChatEntryCodec.class, codec);
		assertEquals(ProtocolKind.CHAT, codec.kind());
		assertSame(codec, extensions.getExtension("chat"), "同一目录内命名实例惰性复用");
		assertNotSame(codec.newStreamEncoder(null, false, ctx()), codec.newStreamEncoder(null, false, ctx()),
				"流式编码器每请求一个实例");
	}

	private static CapabilityContext ctx() {
		return new CapabilityContext("tenant-1", "project-1", new DegradationAudit(DegradationGrant.empty()),
				new ContinuationEnvelope("chat-discovery-test-key".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void unknownNameFailsClearly() {
		Extensions<EntryCodec> extensions = Extensions.load(EntryCodec.class);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> extensions.getExtension("no-such-entry"));
		assertTrue(failure.getMessage().contains("no-such-entry"), failure.getMessage());
		assertThrows(IllegalStateException.class, () -> extensions.getExtension(null));
		assertThrows(IllegalStateException.class, extensions::getDefaultExtension);
	}

}