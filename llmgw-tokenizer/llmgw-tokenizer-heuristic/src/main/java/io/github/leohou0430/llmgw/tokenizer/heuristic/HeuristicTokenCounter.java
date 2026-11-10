package io.github.leohou0430.llmgw.tokenizer.heuristic;

import io.github.leohou0430.llmgw.tokenizer.TokenCounter;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;

/** Reuses the parser estimate so the default path never scans a prompt twice. */
public final class HeuristicTokenCounter implements TokenCounter {
    public int count(String text) { return ProtocolCodec.estimateTokens(text); }
    public String name() { return "heuristic"; }
    @Override public int estimate(ParsedRequest request) { return request.estimatedTokens(); }
}
