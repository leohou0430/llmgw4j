package io.github.leohou0430.llmgw.core.protocol;

/**
 * Minimal, intentionally thin view of a protocol request. The gateway never
 * deserializes the full wire payload: unknown/vendor-specific fields stay in
 * {@link #rawJson()} and are forwarded byte-for-byte.
 *
 * @param kind    the protocol this body was parsed as; drives routing, SSE
 *                terminator detection and response-side token/cache semantics
 * @param units   request units billed for media kinds (images: n images,
 *                videos create: 1 task; text protocols always 1)
 * @param cacheFingerprint hash of the COMPLETE request body, used as the
 *                         semantic-cache key so sampling parameters, tools and
 *                         multimodal content can never collide with a plain
 *                         text prompt that happens to be identical. Null for
 *                         stream=true requests and for media kinds (never
 *                         cached): hashing a 4-16KB body costs 68-70% of the
 *                         parse. GovernanceChain computes it on demand if a
 *                         custom implementation ever needs a key for such a
 *                         request.
 */
public record ParsedRequest(
        String rawJson,
        ProtocolKind kind,
        String model,
        boolean stream,
        String promptText,
        int estimatedTokens,
        int units,
        String cacheFingerprint) {

    /** Requests without a body (video task polling / content download). */
    public static ParsedRequest bodyless(ProtocolKind kind) {
        return new ParsedRequest("", kind, "", false, "", 0, 1, null);
    }
}
