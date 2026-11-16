package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

public record TextPart(String text) implements ContentPart {
    public TextPart {
        Objects.requireNonNull(text, "text");
    }
}