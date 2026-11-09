package io.github.leohou0430.llmgw.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface-layer contract of the protocol codecs. The byte-faithful pass-through
 * architecture leans on exactly these guarantees: minimal extraction, byte-safe
 * model rewrite, whole-body cache fingerprints, and per-protocol SSE contracts.
 */
class ProtocolCodecTest {

    @Test
    void chatParseExtractsModelStreamAndPromptSkippingUnknownFields() {
        String body = """
                {"model":"qwen-plus","stream":true,"temperature":0.7,
                 "messages":[{"role":"system","content":"be brief"},
                             {"role":"user","content":"hello "},{"content":"world"},
                             {"role":"user","content":[{"type":"text","text":"multimodal"}]}],
                 "tools":[{"type":"function"}]}
                """;
        ParsedRequest parsed = ProtocolCodec.parse(ProtocolKind.CHAT, body);
        assertEquals("qwen-plus", parsed.model());
        assertTrue(parsed.stream());
        // Textual message contents concatenate; multimodal content lists are skipped.
        assertEquals("be briefhello world", parsed.promptText());
        assertEquals(ProtocolKind.CHAT, parsed.kind());
        assertEquals(1, parsed.units());
        // stream=true requests carry no cache fingerprint (never cached).
        assertNull(parsed.cacheFingerprint());
    }

    @Test
    void nonStreamingChatCarriesWholeBodyFingerprint() {
        ParsedRequest a = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":\"hi\"}]}");
        ParsedRequest b = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":\"hi\"}]}");
        ParsedRequest different = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":\"hi\"}],\"temperature\":0}");
        assertEquals(a.cacheFingerprint(), b.cacheFingerprint(), "identical bodies fingerprint identically");
        assertNotEquals(a.cacheFingerprint(), different.cacheFingerprint(),
                "sampling parameters must not collide with the plain prompt");
    }

    @Test
    void malformedBodyThrowsForGateway400() {
        assertThrows(Exception.class, () -> ProtocolCodec.parse(ProtocolKind.CHAT, "{not json"));
        assertThrows(Exception.class, () -> ProtocolCodec.parse(ProtocolKind.CHAT, "[1,2]"));
    }

    @Test
    void imagesParseBillsUnits() {
        ParsedRequest parsed = ProtocolCodec.parse(ProtocolKind.IMAGES,
                "{\"model\":\"img\",\"prompt\":\"a cat\",\"n\":3}");
        assertEquals(3, parsed.units());
        assertEquals("a cat", parsed.promptText());
        // Images are never cached: no fingerprint regardless of stream flag.
        assertNull(parsed.cacheFingerprint());
    }

    @Test
    void modelRewriteChangesOnlyTopLevelModelNode() {
        String body = "{\"model\":\"gpt-x\",\"messages\":[{\"content\":\"a\\\"b\"}],\"nested\":{\"model\":\"keep\"}}";
        String rewritten = ProtocolCodec.withModel(body, "qwen-plus");
        assertTrue(rewritten.contains("\"qwen-plus\""));
        assertTrue(rewritten.contains("\"keep\""), "nested model fields must survive");
        assertTrue(rewritten.contains("a\\\"b"), "escaped content must survive byte-for-byte");
        // Rewriting to the same model returns the original body untouched.
        assertEquals(body, ProtocolCodec.withModel(body, "gpt-x"));
    }

    @Test
    void truncationWordingIsPerProtocol() {
        assertEquals("upstream closed SSE stream without [DONE]",
                ProtocolCodec.truncatedMessage(ProtocolKind.CHAT));
        assertEquals("upstream closed SSE stream without response.completed",
                ProtocolCodec.truncatedMessage(ProtocolKind.RESPONSES));
        assertEquals("upstream closed SSE stream without message_stop",
                ProtocolCodec.truncatedMessage(ProtocolKind.MESSAGES));
    }

    @Test
    void completionTokensComeFromAuthoritativeUsageFrame() {
        String chat = "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7}}";
        assertEquals(7, ProtocolCodec.completionTokensFromResponse(ProtocolKind.CHAT, chat));
        // Without a usage frame the content estimate is the fallback.
        String noUsage = "{\"choices\":[{\"message\":{\"content\":\"hello world\"}}]}";
        assertEquals(ProtocolCodec.estimateTokens("hello world"),
                ProtocolCodec.completionTokensFromResponse(ProtocolKind.CHAT, noUsage));
        // Media kinds never generate tokens, even with a usage frame present.
        assertEquals(0, ProtocolCodec.completionTokensFromResponse(ProtocolKind.IMAGES, chat));
    }

    @Test
    void cacheabilityRequiresAWellFormedChatCompletion() {
        assertTrue(ProtocolCodec.isCacheableResponse(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],\"usage\":{}}"));
        assertFalse(ProtocolCodec.isCacheableResponse("{\"error\":{\"message\":\"x\"}}"));
        assertFalse(ProtocolCodec.isCacheableResponse("not json at all"));
    }

    @Test
    void errorContractShapesAreStable() {
        assertEquals("{\"error\":{\"message\":\"boom\",\"type\":\"gateway_error\"}}",
                ProtocolCodec.errorJson("boom"));
        assertEquals("event: error\ndata: {\"error\":{\"message\":\"boom\",\"type\":\"gateway_error\"}}\n\n",
                ProtocolCodec.errorEvent("boom"));
        assertEquals("data: [DONE]\n\n", ProtocolCodec.doneLine());
    }

    @Test
    void estimateTokensIsDeterministicAndRough() {
        assertEquals(ProtocolCodec.estimateTokens("hello world"),
                ProtocolCodec.estimateTokens("hello world"));
        assertTrue(ProtocolCodec.estimateTokens("hello world") > 0);
    }
}
