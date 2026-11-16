package io.github.leohou0430.llmgw.catalog;

import java.util.Map;

/**
 * Intent to create an upstream resource. {@code upstreamId} is not yet known — the
 * {@link UpstreamCreator} assigns it. {@code sourceRoute}/{@code configVersion} are
 * captured at creation so the committed entry keeps this source even if the gateway
 * default route later changes.
 */
public record CreationRequest(
        String projectId,
        String gatewayId,
        String type,
        String sourceRoute,
        String provider,
        String configVersion,
        Map<String, String> metadata) {

    public CreationRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}