package io.github.leohou0430.llmgw.conversion;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;

/**
 * Conversion orchestration entry point (T1.11): ties the discovered entry/upstream codecs together
 * for one request. A plain api contract bound by the named {@code conversion} ExtensionModule, not an
 * {@code @ExtensionPoint} itself (there is exactly one orchestration, like {@code GovernanceChain}).
 *
 * <p>Implementations are stateless and shareable: every per-request state lives in the
 * {@link CapabilityContext} handed to the codecs and in the {@link PreparedConversion} /
 * {@link StreamTranscoder} instances returned here.
 */
public interface ConversionService {

    /**
     * Whether this route asks for protocol conversion. True iff {@link Route#upstreamProtocol()} is
     * non-blank (stage 1: {@code "chat"}); blank means the existing byte-for-byte passthrough.
     */
    boolean applies(Route route);

    /**
     * Decode the caller request with its entry codec and encode the upstream request payload.
     * The returned handle owns the per-request {@link CapabilityContext} (tenant/project ownership,
     * degradation audit, continuation envelope) and must be closed by the caller.
     *
     * @throws CapabilityException if no entry codec serves {@link ParsedRequest#kind()} (501) or the
     *                             request violates an entry capability guard
     * @throws UpstreamException   if the semantic request cannot be expressed upstream
     */
    PreparedConversion prepare(ParsedRequest parsed, Route route, String tenantId, String projectId,
                               DegradationGrant grant);

    /**
     * T1.12 engine seam: render a PRE-HEADER failure in the caller's entry error shape (matrix
     * §10.2) so the data planes never re-discover codecs. The HTTP status stays the exception's own
     * {@link CapabilityException#status()}; only the body shape is entry-specific.
     *
     * <p>Default: the gateway-generic error body, for orchestrations that discover no entry codec
     * (e.g. a replacement bound before seal). Codec-aware implementations override it.
     */
    default String errorJson(io.github.leohou0430.llmgw.core.protocol.ProtocolKind kind,
                             CapabilityException error) {
        return io.github.leohou0430.llmgw.core.protocol.ProtocolCodec.errorJson(error.getMessage());
    }
}