package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/** {@code text} 非空但允许空串：空串增量与缺失载荷语义不同。 */
public record TextDelta(String text) implements SemanticEvent {
	public TextDelta {
		Objects.requireNonNull(text, "text");
	}
}