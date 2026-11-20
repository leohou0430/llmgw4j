package io.github.leohou0430.llmgw.conversion;

import java.util.List;

/**
 * Streaming transcoder for one request: upstream chat SSE lines -> entry-protocol SSE
 * lines. Wraps exactly one {@link StreamDecoder} and one {@link StreamEncoder}; all
 * mutable state lives in those two instances, so a transcoder is never shared and never
 * buffers a growing line/event list.
 *
 * <p>
 * Returned lines are the encoder output verbatim (they already carry the {@code "data:"}
 * prefix, and the Responses/Messages encoders also carry their {@code "event:"} line);
 * framing/newlines belong to the transport above.
 *
 * <p>
 * Truncation is never turned into success: an upstream end without a terminal emits an
 * entry-protocol error event instead of a success terminal.
 */
public interface StreamTranscoder extends AutoCloseable {

	/**
	 * One upstream chat SSE line -> 0..n entry-protocol lines. Non-data/blank/comment
	 * lines yield none.
	 */
	List<String> onUpstreamLine(String line);

	/**
	 * Upstream normal end: emit the terminal once; a missing terminal yields an error
	 * event, never fake success.
	 */
	List<String> onUpstreamComplete();

	/**
	 * Error after the response headers were sent: an entry-protocol error event, then
	 * terminate.
	 */
	List<String> onUpstreamError(Throwable t);

	/** True once a terminal frame or an error frame has been emitted. */
	boolean terminalSeen();

	@Override
	void close();

}