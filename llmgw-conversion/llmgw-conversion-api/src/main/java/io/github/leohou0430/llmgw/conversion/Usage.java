package io.github.leohou0430.llmgw.conversion;

/**
 * 每个字段 null=未知，绝不默认 0：未知与 0 在计费/统计上语义不同。 {@code cachedInputTokens} 为输入中的缓存命中子集（Chat
 * cached_tokens / Messages cache_read_input_tokens）。
 */
public record Usage(Long inputTokens, Long outputTokens, Long reasoningTokens, Long cachedInputTokens) {
}