package io.github.leohou0430.llmgw.conversion.module;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.UpstreamCodec;
import io.github.leohou0430.llmgw.conversion.UpstreamPayload;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.routing.Route;

import java.util.Map;
import java.util.Objects;

/**
 * Stateless, shareable orchestration: the discovered codecs and the runtime-owned
 * envelope are final, and every per-request state is created inside {@link #prepare}.
 * Nothing here is a static registry.
 */
final class DefaultConversionService implements ConversionService {

	private final Map<ProtocolKind, EntryCodec> entryCodecs;

	/**
	 * Route selection: upstreamProtocol names the dialect; blank = default (deepseek).
	 */
	private UpstreamCodec upstreamFor(Route route) {
		String protocol = route == null || route.upstreamProtocol() == null || route.upstreamProtocol().isBlank()
				? defaultUpstreamName : route.upstreamProtocol();
		String name = PROTOCOL_ALIASES.getOrDefault(protocol, protocol);
		UpstreamCodec codec = upstreams.get(name);
		if (codec == null) {
			throw new io.github.leohou0430.llmgw.conversion.UpstreamException(
					"no upstream codec for route upstreamProtocol '" + protocol + "'");
		}
		return codec;
	}

	private final ContinuationEnvelope envelope;

	/** Route.upstreamProtocol -> codec name. Legacy "chat" keeps the deepseek meaning. */
	private static final Map<String, String> PROTOCOL_ALIASES = Map.of("chat", "deepseek-chat");

	private final Map<String, UpstreamCodec> upstreams;

	private final String defaultUpstreamName;

	DefaultConversionService(Map<ProtocolKind, EntryCodec> entryCodecs, Map<String, UpstreamCodec> upstreams,
			String defaultUpstreamName, ContinuationEnvelope envelope) {
		this.entryCodecs = Map.copyOf(entryCodecs);
		this.upstreams = Map.copyOf(upstreams);
		this.defaultUpstreamName = Objects.requireNonNull(defaultUpstreamName, "defaultUpstreamName");
		this.envelope = Objects.requireNonNull(envelope, "envelope");
		if (!this.upstreams.containsKey(defaultUpstreamName)) {
			throw new IllegalStateException("default upstream codec missing: " + defaultUpstreamName);
		}
	}

	@Override
	public boolean applies(Route route) {
		return route != null && route.upstreamProtocol() != null && !route.upstreamProtocol().isBlank();
	}

	@Override
	public PreparedConversion prepare(ParsedRequest parsed, Route route, String tenantId, String projectId,
			DegradationGrant grant) {
		Objects.requireNonNull(parsed, "parsed");
		Objects.requireNonNull(route, "route");
		EntryCodec entry = entryCodecs.get(parsed.kind());
		if (entry == null) {
			throw new CapabilityException(CapabilityException.NOT_SUPPORTED, 501, "protocol",
					"no entry codec for " + parsed.kind());
		}
		// One context per request: ownership, degradation audit and the runtime-owned
		// envelope.
		CapabilityContext context = new CapabilityContext(tenantId, projectId, new DegradationAudit(grant), envelope);
		SemanticRequest semantic = entry.decode(parsed.rawJson(), context);
		String targetModel = route.targetModel() == null || route.targetModel().isBlank() ? semantic.model()
				: route.targetModel();
		UpstreamCodec upstream = upstreamFor(route);
		UpstreamPayload payload = upstream.encodeRequest(semantic, targetModel);
		return new DefaultPreparedConversion(entry, upstream, context, parsed.model(),
				Boolean.TRUE.equals(semantic.includeUsage()), payload);
	}

	/**
	 * T1.12: the entry codec owns the error shape (matrix §10.2). A kind without an entry
	 * codec keeps the gateway-generic body rather than failing the error path itself.
	 */
	@Override
	public String errorJson(ProtocolKind kind, CapabilityException error) {
		EntryCodec entry = entryCodecs.get(kind);
		return entry == null ? ConversionService.super.errorJson(kind, error) : entry.errorJson(error);
	}

}