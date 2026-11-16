package io.github.leohou0430.llmgw.catalog;

import java.util.Map;

/**
 * A resource in the legacy key space. It belongs to {@code legacyOwner} and is kept in
 * that space until an explicit, reviewed mapping shares it into a tenant/project. It is
 * never imported by scanning a provider account.
 */
public record LegacyResource(
        String legacyOwner,
        String legacyId,
        String type,
        String sourceRoute,
        String upstreamId,
        String provider,
        String configVersion,
        Map<String, String> metadata) {

    public LegacyResource {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}