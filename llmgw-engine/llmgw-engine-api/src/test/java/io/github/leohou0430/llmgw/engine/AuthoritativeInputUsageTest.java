package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthoritativeInputUsageTest {
    @Test void authoritativeInputOverridesEstimateWithoutChangingReservation() {
        var context=new GatewayContext("key","model","route",true,40);
        SseChunkAccounting.apply(ProtocolKind.CHAT,"data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}",context);
        assertEquals(1,context.promptTokens());assertEquals(2,context.completionTokens());
        assertEquals(40,context.reservedTokens());
    }
    @Test void messagesInputCanArriveBeforeOutputAndZeroIsAuthoritative() {
        var context=new GatewayContext("key","model","route",true,40);
        SseChunkAccounting.apply(ProtocolKind.MESSAGES,"event: message_start\ndata: {\"message\":{\"usage\":{\"input_tokens\":0}}}",context);
        assertEquals(0,context.promptTokens());
        SseChunkAccounting.apply(ProtocolKind.MESSAGES,"data: {\"usage\":{\"output_tokens\":3}}",context);
        assertEquals(0,context.promptTokens());assertEquals(3,context.completionTokens());
    }
    @Test void responsesAndMissingUsageKeepIndependentFallbacks() {
        var context=new GatewayContext("key","model","route",true,40);
        SseChunkAccounting.apply(ProtocolKind.RESPONSES,"data: {\"response\":{\"usage\":{\"input_tokens\":2,\"output_tokens\":4}}}",context);
        assertEquals(2,context.promptTokens());assertEquals(4,context.completionTokens());
        SseChunkAccounting.apply(ProtocolKind.RESPONSES,"data: {\"delta\":\"hello\"}",context);
        assertEquals(2,context.promptTokens());assertEquals(4,context.completionTokens());
    }
}
