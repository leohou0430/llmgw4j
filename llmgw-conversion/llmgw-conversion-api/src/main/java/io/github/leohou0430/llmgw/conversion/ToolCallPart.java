package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record ToolCallPart(String id, String name, String argumentsJson) implements ContentPart {
	public ToolCallPart {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");
	}
}