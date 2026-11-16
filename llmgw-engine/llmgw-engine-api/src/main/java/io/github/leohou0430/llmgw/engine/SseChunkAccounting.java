package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.protocol.ChunkParser;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

/**
 * Token accounting for one raw SSE line, identical for both data planes.
 *
 * <p>The authoritative usage frame REPLACES the accumulated per-delta estimate;
 * adding it on top double-bills a normal stream. Parsing never rewrites the
 * line -- the caller still forwards {@code rawSseLine} verbatim. Each protocol
 * has its own terminator frame and delta/usage locations (see
 * {@link ChunkParser#parse(ProtocolKind, String)}).
 *
 * <p>First-chunk marking is intentionally NOT here: the reactive engine tracks
 * it with an {@code AtomicBoolean} on a Netty event loop, while the virtual
 * thread engine uses a plain boolean (one request = one thread). Forcing a
 * shared concurrency primitive would destroy the single-variable A/B setup.
 *
 * @return true when this line is the protocol terminator
 */
public final class SseChunkAccounting {

    private SseChunkAccounting() {
    }

    public static boolean apply(String rawSseLine, GatewayContext context) {
        return apply(ProtocolKind.CHAT, rawSseLine, context);
    }

    public static boolean apply(ProtocolKind kind, String rawSseLine, GatewayContext context) {
        ChunkParser.SseChunk chunk = io.github.leohou0430.llmgw.core.protocol.Protocols
                .of(kind).parseChunk(rawSseLine);
        // Input usage may arrive separately (Messages message_start) from output usage.
        if (rawSseLine != null) {
            for (String line : rawSseLine.split("\\r?\\n")) {
                if (!line.stripLeading().startsWith("data:")) continue;
                try {
                    var root = io.github.leohou0430.llmgw.core.protocol.ProtocolCodec.mapper()
                            .readTree(line.stripLeading().substring(5).trim());
                    var usage = root.path("usage");
                    if (usage.isMissingNode()) usage = root.path("response").path("usage");
                    if (usage.isMissingNode()) usage = root.path("message").path("usage");
                    var input = usage.path(kind == ProtocolKind.CHAT ? "prompt_tokens" : "input_tokens");
                    if (input.isIntegralNumber() && input.canConvertToInt() && input.intValue() >= 0)
                        context.setPromptTokens(input.intValue());
                } catch (RuntimeException ignored) { /* passthrough */ }
            }
        }
        if (chunk.hasUsage()) {
            context.setCompletionTokens(chunk.completionTokens());
        } else if (!chunk.done() && !chunk.delta().isEmpty()) {
            context.addDeltaTokens(chunk.delta());
        }
        return chunk.done();
    }
}
