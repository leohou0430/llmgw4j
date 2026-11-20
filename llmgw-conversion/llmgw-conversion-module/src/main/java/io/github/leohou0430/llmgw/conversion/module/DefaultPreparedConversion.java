package io.github.leohou0430.llmgw.conversion.module;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;
import io.github.leohou0430.llmgw.conversion.UpstreamCodec;
import io.github.leohou0430.llmgw.conversion.UpstreamPayload;

import java.util.Objects;

/**
 * One prepared request. Everything held here is an immutable value or a shareable
 * stateless codec; the mutable per-request state is created lazily, one
 * {@link StreamTranscoder} at a time.
 *
 * <p>
 * {@code callerModel} is the model the CALLER asked for, which is what the entry protocol
 * must echo back -- never the rewritten upstream model.
 */
final class DefaultPreparedConversion implements PreparedConversion {

	private final EntryCodec entry;

	private final UpstreamCodec upstream;

	private final CapabilityContext context;

	private final String callerModel;

	private final boolean includeUsage;

	private final UpstreamPayload payload;

	DefaultPreparedConversion(EntryCodec entry, UpstreamCodec upstream, CapabilityContext context, String callerModel,
			boolean includeUsage, UpstreamPayload payload) {
		this.entry = Objects.requireNonNull(entry, "entry");
		this.upstream = Objects.requireNonNull(upstream, "upstream");
		this.context = Objects.requireNonNull(context, "context");
		this.callerModel = callerModel;
		this.includeUsage = includeUsage;
		this.payload = Objects.requireNonNull(payload, "payload");
	}

	@Override
	public String upstreamJson() {
		return payload.rawJson();
	}

	@Override
	public String upstreamPath() {
		return payload.path();
	}

	@Override
	public String transcodeResponse(String upstreamJson) {
		return entry.encodeResponse(upstream.decodeResponse(upstreamJson), callerModel, context);
	}

	@Override
	public StreamTranscoder newStreamTranscoder() {
		return new DefaultStreamTranscoder(upstream.newStreamDecoder(),
				entry.newStreamEncoder(callerModel, includeUsage, context));
	}

	@Override
	public void close() {
		// No native resource is held: the payload and the capability context are
		// immutable values and
		// the codecs are shareable. The mutable streaming state belongs to each
		// StreamTranscoder and
		// is released when that transcoder is closed, so closing this handle is a
		// repeatable no-op.
	}

}