package io.github.leohou0430.llmgw.conversion;

/**
 * One prepared request conversion: the upstream payload plus the response-side transcoders.
 * Per-request and single-use; the caller model (not the rewritten upstream model) is what the
 * entry protocol sees on the way back.
 *
 * <p>Closing releases the handle. It holds no native resources: the mutable streaming state belongs
 * to the {@link StreamTranscoder} instances created from it, each of which must be closed too.
 */
public interface PreparedConversion extends AutoCloseable {

    /** Upstream request body, already rewritten to the upstream protocol. */
    String upstreamJson();

    /** Upstream endpoint path appended to the route base URL (DeepSeek Chat: {@code /chat/completions}). */
    String upstreamPath();

    /** Non-streaming: upstream response JSON -> entry-protocol response JSON. */
    String transcodeResponse(String upstreamJson);

    /** Streaming: a fresh transcoder, one per request (never shared across requests). */
    StreamTranscoder newStreamTranscoder();

    @Override
    void close();
}