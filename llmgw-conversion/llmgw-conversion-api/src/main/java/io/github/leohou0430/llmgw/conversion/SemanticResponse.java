package io.github.leohou0430.llmgw.conversion;

import java.util.List;
import java.util.Objects;

public record SemanticResponse(String id, List<SemanticMessage> messages, StopReason stop, Usage usage) {
    public SemanticResponse {
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }
}