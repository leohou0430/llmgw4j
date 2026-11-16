package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record TerminalEvent(StopReason stop) implements SemanticEvent {
    public TerminalEvent {
        Objects.requireNonNull(stop, "stop");
    }
}