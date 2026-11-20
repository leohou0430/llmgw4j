package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/** {@code reasoning} 非空但允许空串，与 {@link ThinkingPart} 一致。 */
public record ThinkingDelta(String reasoning) implements SemanticEvent {
	public ThinkingDelta {
		Objects.requireNonNull(reasoning, "reasoning");
	}
}