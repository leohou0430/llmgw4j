package io.github.leohou0430.llmgw.core.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v13 Protocol SPI: all per-protocol behavior must be reachable through
 * {@link Protocols#of(ProtocolKind)}, and a custom registration must override
 * the default for its kind. This is the seam that replaced four closed
 * switches; a sixth protocol will be one implementation + one enum entry.
 */
class ProtocolSpiTest {

    @AfterEach
    void restoreDefaults() {
        Protocols.resetToDefaults();
    }

    @Test
    void allFiveKindsResolveThroughTheRegistry() {
        for (ProtocolKind kind : ProtocolKind.values()) {
            assertEquals(kind, Protocols.of(kind).kind());
        }
    }

    @Test
    void chatIsTheOnlyProtocolThatAppendsDoneAfterInBandErrors() {
        assertTrue(Protocols.of(ProtocolKind.CHAT).appendDoneAfterInBandError());
        assertFalse(Protocols.of(ProtocolKind.RESPONSES).appendDoneAfterInBandError());
        assertFalse(Protocols.of(ProtocolKind.MESSAGES).appendDoneAfterInBandError());
        assertFalse(Protocols.of(ProtocolKind.IMAGES).appendDoneAfterInBandError());
        assertFalse(Protocols.of(ProtocolKind.VIDEOS).appendDoneAfterInBandError());
    }

    @Test
    void mediaProtocolsNeverCacheAndNeverEmitTokens() {
        String chatLikeBody = "{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":{\"completion_tokens\":5}}";
        assertFalse(Protocols.of(ProtocolKind.IMAGES).cacheable(null));
        assertFalse(Protocols.of(ProtocolKind.VIDEOS).cacheable(chatLikeBody
                .isEmpty() ? null : Protocols.of(ProtocolKind.CHAT).parse(chatLikeBody) == null
                ? null : tools.jackson.databind.json.JsonMapper.builder().build().readTree(chatLikeBody)));
        assertEquals(0, Protocols.of(ProtocolKind.IMAGES).completionTokens(
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                        "{\"usage\":{\"completion_tokens\":9}}")));
    }

    @Test
    void videosIsTheAsyncJobProtocol() {
        Protocol videos = Protocols.of(ProtocolKind.VIDEOS);
        var root = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree("{\"id\":\"video-42\",\"status\":\"queued\"}");
        assertEquals("video-42", videos.taskId(root));
        assertEquals("", Protocols.of(ProtocolKind.CHAT).taskId(root));
    }

    @Test
    void customRegistrationOverridesTheDefaultForItsKind() {
        Protocol countingChat = new Protocol() {
            @Override public ProtocolKind kind() {
                return ProtocolKind.CHAT;
            }

            @Override public ParsedRequest parse(String rawJson) {
                return Protocols.of(ProtocolKind.CHAT) == this
                        ? null
                        : DefaultProtocolsAccess.parseChat(rawJson);
            }

            @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
                return ChunkParser.parse(rawLine);
            }

            @Override public int completionTokens(tools.jackson.databind.JsonNode root) {
                return 31;
            }

            @Override public boolean cacheable(tools.jackson.databind.JsonNode root) {
                return false;
            }

            @Override public boolean appendDoneAfterInBandError() {
                return true;
            }
        };
        Protocol defaultChat = Protocols.of(ProtocolKind.CHAT);
        Protocols.register(countingChat);
        assertSame(countingChat, Protocols.of(ProtocolKind.CHAT));
        // The override flows through the static facade the engines use.
        assertEquals(31, ProtocolCodec.completionTokensFromResponse(
                ProtocolKind.CHAT,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                        "{\"usage\":{\"completion_tokens\":7}}")));
        Protocols.register(defaultChat);
        assertSame(defaultChat, Protocols.of(ProtocolKind.CHAT));
    }

    /** Test escape hatch: the default chat parse without touching the registry. */
    private static final class DefaultProtocolsAccess {
        static ParsedRequest parseChat(String rawJson) {
            return ProtocolCodec.parse(rawJson);
        }
    }
}
