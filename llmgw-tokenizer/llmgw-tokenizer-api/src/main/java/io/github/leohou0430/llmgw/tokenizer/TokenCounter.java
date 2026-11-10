package io.github.leohou0430.llmgw.tokenizer;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;

/** Named admission estimator. Authoritative upstream usage still overrides final accounting. */
@ExtensionPoint("heuristic")
public interface TokenCounter {
    int count(String text);
    String name();
    default int estimate(ParsedRequest request) {
        return switch (request.kind()) {
            case CHAT, RESPONSES, MESSAGES, EMBEDDINGS, RERANK -> Math.max(0,count(request.promptText()));
            default -> request.estimatedTokens();
        };
    }
}
