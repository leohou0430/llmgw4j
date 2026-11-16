package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/** {@code continuationToken} 为网关命名空间续轮信封；Chat 入口原生回传 reasoning_content 时为 null。 */
public record ThinkingPart(String reasoning, String continuationToken) implements ContentPart {
    public ThinkingPart {
        Objects.requireNonNull(reasoning, "reasoning");
    }
}