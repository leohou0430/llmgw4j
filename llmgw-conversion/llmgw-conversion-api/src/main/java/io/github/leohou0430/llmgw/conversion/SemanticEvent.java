package io.github.leohou0430.llmgw.conversion;

public sealed interface SemanticEvent permits TextDelta, ThinkingDelta, ToolCallDelta, UsageEvent, TerminalEvent {
}