package io.github.leohou0430.llmgw.conversion.deepseek;

import io.github.leohou0430.llmgw.conversion.UpstreamCodec;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekCodecDiscoveryTest {

	@Test
	void namedDiscoveryFindsDeepSeekChat() {
		Extensions<UpstreamCodec> extensions = Extensions.load(UpstreamCodec.class);
		assertTrue(extensions.hasExtension("deepseek-chat"));
		UpstreamCodec codec = extensions.getExtension("deepseek-chat");
		assertInstanceOf(DeepSeekChatCodec.class, codec);
		assertSame(codec, extensions.getExtension("deepseek-chat"));
	}

	@Test
	void unknownNameFailsClearly() {
		Extensions<UpstreamCodec> extensions = Extensions.load(UpstreamCodec.class);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> extensions.getExtension("no-such-upstream"));
		assertTrue(failure.getMessage().contains("no-such-upstream"), failure.getMessage());
		assertThrows(IllegalStateException.class, () -> extensions.getExtension(null));
	}

}