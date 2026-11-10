package io.github.leohou0430.llmgw.core.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SsePayloadBoundaryTest {
    @Test void whitespaceExtractionPreservesDeltasAndOriginalWireBytes() {
        String[] payloads = {
                "{\"choices\":[{\"delta\":{\"content\":\"你好\\nworld\"}}]}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"你好\\nworld\"}",
                "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"你好\\nworld\"}}"
        };
        var kinds = new ProtocolKind[]{ProtocolKind.CHAT,ProtocolKind.RESPONSES,ProtocolKind.MESSAGES};
        for (int i=0;i<kinds.length;i++) {
            for (String prefix : new String[]{"data:","data: ","\t data:\t", "\0 data: "}) {
                for (String suffix : new String[]{"", "\n", "\r\n\r\n", "\0"}) {
                    String raw=prefix+payloads[i]+suffix;
                    var result=ChunkParser.parse(kinds[i],raw);
                    assertEquals(raw,result.raw());
                    assertEquals("你好\nworld",result.delta());
                    assertFalse(result.done());
                }
            }
        }
    }
    @Test void commentEmptyMalformedAndNonAsciiPrefixStayPassthrough() {
        for(var kind:new ProtocolKind[]{ProtocolKind.CHAT,ProtocolKind.RESPONSES,ProtocolKind.MESSAGES}) {
            for(String raw:new String[]{"", " \t\r\n", ": keep-alive\n\n", "event: delta\n", "data:\n", "data: {broken}\n", "\u2003data: [DONE]\n"}) {
                var result=ChunkParser.parse(kind,raw);
                assertEquals(raw,result.raw());assertEquals("",result.delta());
                assertFalse(result.done());assertFalse(result.hasUsage());
            }
        }
    }
    @Test void usageAndTerminatorsSurviveOuterWhitespace() {
        String chat="\tdata: {\"usage\":{\"completion_tokens\":12}}\r\n";
        var usage=ChunkParser.parse(ProtocolKind.CHAT,chat);
        assertTrue(usage.hasUsage());assertEquals(12,usage.completionTokens());assertEquals(chat,usage.raw());
        assertTrue(ChunkParser.parse(ProtocolKind.CHAT," data: [DONE]\r\n").done());
        assertTrue(ChunkParser.parse(ProtocolKind.RESPONSES," data: {\"type\":\"response.completed\"}\n").done());
        assertTrue(ChunkParser.parse(ProtocolKind.MESSAGES," data: {\"type\":\"message_stop\"}\n").done());
    }
}
