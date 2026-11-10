package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.databind.JsonNode;

/**
 * The protocol SPI (v13). Before this seam, protocol behavior was scattered
 * across four closed switches ({@code ProtocolCodec.parse},
 * {@code ChunkParser.parse}, {@code completionTokensFromResponse},
 * {@code isCacheableResponse}) plus kind branches in both data planes. Adding
 * a protocol meant touching all of them; now it means ONE implementation of
 * this interface, registered under its {@link ProtocolKind}.
 *
 * <p>{@link ProtocolKind} remains the catalog of wire protocols (routing
 * config, metrics tags and {@code upstreamPath} live there); this interface
 * owns the BEHAVIOR. Byte-faithful pass-through still rules: {@link #parse}
 * extracts only what governance needs, {@link #parseChunk} never rewrites a
 * line, and no method ever re-serializes a payload.
 */
@io.github.leohou0430.llmgw.core.extension.ExtensionPoint("chat")
public interface Protocol {

    ProtocolKind kind();

    /**
     * Minimal request probe: model / stream / prompt text / billing units.
     * Malformed JSON throws so the gateway answers 400.
     */
    ParsedRequest parse(String rawJson);

    /**
     * W4.3 byte-body probe (multipart uploads): implementations that accept
     * binary bodies override this; the default decodes UTF-8 and calls
     * {@link #parse(String)} — correct for JSON kinds only.
     */
    default ParsedRequest parse(byte[] body) {
        return parse(new String(body == null ? new byte[0] : body, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Accounting for one raw SSE line: deltas, the authoritative usage frame
     * and the terminator verdict. The raw line is never rewritten.
     */
    ChunkParser.SseChunk parseChunk(String rawLine);

    /** Completion tokens of a non-streaming response body (usage frame first). */
    int completionTokens(JsonNode root);

    /** Whether a non-streaming response body may enter the semantic cache. */
    boolean cacheable(JsonNode root);

    /**
     * CHAT-only contract: after an in-band error the gateway appends the
     * protocol's own terminator ({@code [DONE]}), because chat clients wait
     * for it. Fabricating response.completed / message_stop would corrupt
     * those clients' protocol view, so the other kinds close the stream bare.
     */
    boolean appendDoneAfterInBandError();

    /**
     * Async-job protocols (videos) return the task id of a create/poll body
     * so the engine can pin polling to the creating route; others return "".
     */
    default String taskId(JsonNode root) {
        return "";
    }
}
