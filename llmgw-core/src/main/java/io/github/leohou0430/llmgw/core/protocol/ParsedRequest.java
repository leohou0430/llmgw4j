package io.github.leohou0430.llmgw.core.protocol;

/**
 * Minimal, intentionally thin view of an OpenAI-compatible chat completion request.
 * The gateway never deserializes the full wire payload: unknown/vendor-specific
 * fields stay in {@link #rawJson()} and are forwarded byte-for-byte.
 *
 * @param cacheFingerprint hash of the COMPLETE request body, used as the
 *                         semantic-cache key so sampling parameters, tools and
 *                         multimodal content can never collide with a plain
 *                         text prompt that happens to be identical. Null for
 *                         stream=true requests: they are never cached, and
 *                         hashing a 4-16KB body costs 68-70% of the parse.
 *                         GovernanceChain computes it on demand if a custom
 *                         implementation ever needs a key for such a request.
 */
public record ParsedRequest(
        String rawJson,
        String model,
        boolean stream,
        String promptText,
        int estimatedTokens,
        String cacheFingerprint) {
}
