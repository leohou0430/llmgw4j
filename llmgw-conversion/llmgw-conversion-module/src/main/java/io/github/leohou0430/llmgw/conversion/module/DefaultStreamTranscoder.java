package io.github.leohou0430.llmgw.conversion.module;

import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.StreamDecoder;
import io.github.leohou0430.llmgw.conversion.StreamEncoder;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Upstream chat SSE lines -> entry-protocol SSE lines, for exactly one request.
 *
 * <p>
 * Owns one {@link StreamDecoder} and one {@link StreamEncoder}; both are per-request
 * stateful instances. Nothing accumulates here: each call builds its own result list and
 * returns it, so the transcoder never holds a growing buffer of lines or events.
 *
 * <p>
 * Truncation is never laundered into success. {@link StreamEncoder#onComplete()} refuses
 * to emit a success terminal when no terminal was seen, so an upstream end without one is
 * turned into an entry-protocol error event instead.
 */
final class DefaultStreamTranscoder implements StreamTranscoder {

	private static final String DATA_PREFIX = "data:";

	private static final String DONE = "[DONE]";

	private static final String UPSTREAM_ERROR = "upstream_error";

	private static final String TRUNCATED = "upstream stream ended before terminal";

	private static final String UNKNOWN_ERROR = "upstream error";

	// Final so the per-request codec state is safely published and never nulled: close()
	// only flips
	// the inert flag, which keeps terminalSeen() a monotonic latch and avoids an NPE if a
	// cancel
	// signal races an in-flight line. The codecs hold no native resource, so they are
	// released with
	// this short-lived transcoder rather than by nulling.
	private final StreamDecoder decoder;

	private final StreamEncoder encoder;

	private boolean closed;

	DefaultStreamTranscoder(StreamDecoder decoder, StreamEncoder encoder) {
		this.decoder = Objects.requireNonNull(decoder, "decoder");
		this.encoder = Objects.requireNonNull(encoder, "encoder");
	}

	@Override
	public List<String> onUpstreamLine(String line) {
		if (closed || line == null || line.isBlank()) {
			return List.of();
		}
		String framed = line.strip();
		// SSE comments/keep-alives and any non-data field carry no upstream chunk.
		if (framed.startsWith(":") || !framed.startsWith(DATA_PREFIX)) {
			return List.of();
		}
		String payload = framed.substring(DATA_PREFIX.length()).strip();
		if (payload.isEmpty()) {
			return List.of();
		}
		if (DONE.equals(payload)) {
			return onUpstreamComplete();
		}
		if (encoder.terminalSeen()) {
			return List.of();
		}
		// onChunk never throws: an illegal payload becomes decoder failure state.
		List<SemanticEvent> events = decoder.onChunk(payload);
		if (decoder.failed()) {
			return encoder.onError(UPSTREAM_ERROR, decoder.failureReason());
		}
		if (events.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(events.size());
		for (SemanticEvent event : events) {
			lines.addAll(encoder.onEvent(event));
		}
		return List.copyOf(lines);
	}

	@Override
	public List<String> onUpstreamComplete() {
		if (closed) {
			return List.of();
		}
		if (decoder.failed()) {
			return encoder.onError(UPSTREAM_ERROR, decoder.failureReason());
		}
		if (decoder.terminalSeen()) {
			return encoder.onComplete();
		}
		// No terminal upstream: emit an error event, never a fabricated success terminal.
		return encoder.onError(UPSTREAM_ERROR, TRUNCATED);
	}

	@Override
	public List<String> onUpstreamError(Throwable t) {
		if (closed) {
			return List.of();
		}
		if (decoder.terminalSeen()) {
			// The upstream already delivered its semantic terminal (chat finish_reason /
			// responses
			// response.completed / messages message_stop). A transport error AFTER that
			// does not
			// un-complete the response: finish the entry stream exactly as a clean EOF
			// would, so chat
			// still gets its [DONE] once and the request bills success rather than a
			// phantom failure.
			return encoder.onComplete();
		}
		String message = t == null || t.getMessage() == null ? UNKNOWN_ERROR : t.getMessage();
		return encoder.onError(UPSTREAM_ERROR, message);
	}

	@Override
	public boolean terminalSeen() {
		return encoder.terminalSeen();
	}

	@Override
	public void close() {
		// Mark inert; later on* calls return empty. The latch in terminalSeen() is
		// unaffected, and
		// the final codec references are never dereferenced after close, so a racing
		// cancel is safe.
		closed = true;
	}

}