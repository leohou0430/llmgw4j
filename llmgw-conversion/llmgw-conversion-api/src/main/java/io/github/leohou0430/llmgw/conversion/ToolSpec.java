package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record ToolSpec(String name, String description, String parametersJsonSchema) {
	public ToolSpec {
		Objects.requireNonNull(name, "name");
	}
}