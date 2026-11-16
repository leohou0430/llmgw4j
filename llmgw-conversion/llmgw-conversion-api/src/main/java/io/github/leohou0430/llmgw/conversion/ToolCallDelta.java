package io.github.leohou0430.llmgw.conversion;

/** 按 {@code index} 拼装；id/name/arguments 为 null 表示该增量片未携带对应字段。 */
public record ToolCallDelta(int index, String id, String name, String arguments) implements SemanticEvent {
    public ToolCallDelta {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
    }
}