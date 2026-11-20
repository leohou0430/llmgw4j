package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record ToolResultPart(String toolCallId, String content, boolean isError) implements ContentPart {
	public ToolResultPart {
		Objects.requireNonNull(toolCallId, "toolCallId");
	}
}