package io.github.leohou0430.llmgw.conversion;

import java.util.List;
import java.util.Objects;

public record SemanticMessage(Role role, List<ContentPart> parts) {
	public SemanticMessage {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(parts, "parts");
		parts = List.copyOf(parts);
	}
}