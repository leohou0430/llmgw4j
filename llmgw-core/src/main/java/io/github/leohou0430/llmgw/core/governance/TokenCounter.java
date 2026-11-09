package io.github.leohou0430.llmgw.core.governance;

/**
 * B6 tokenizer SPI. The default is the {@code len/4 + CJK} heuristic in
 * ProtocolCodec; implementations (e.g. the optional llmgw-tokenizer-jtokkit
 * module) plug a REAL tokenizer in behind the same interface — no admission
 * or metering code changes. Used ONLY when an upstream does not report usage
 * frames; authoritative usage always wins.
 */
public interface TokenCounter {

    /** Deterministic token count for one text chunk. */
    int count(String text);

    /** Identity label (metrics/audit provenance). */
    String name();
}
