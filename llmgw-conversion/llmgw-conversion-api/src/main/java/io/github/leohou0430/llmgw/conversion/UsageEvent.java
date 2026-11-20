package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record UsageEvent(Usage usage) implements SemanticEvent {
	public UsageEvent {
		Objects.requireNonNull(usage, "usage");
	}
}