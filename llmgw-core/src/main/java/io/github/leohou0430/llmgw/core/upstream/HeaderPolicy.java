package io.github.leohou0430.llmgw.core.upstream;

import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.util.List;
import java.util.Map;

/**
 * Bidirectional header pass-through policy. By design the gateway forwards
 * neither client headers upstream nor upstream headers back; real protocols
 * need a controlled exceptions channel (anthropic-version, OpenAI-Beta,
 * x-ratelimit-*, retry-after). The policy owns both whitelists so the
 * gateway's security posture (no blind header forwarding) stays in one place.
 */
public interface HeaderPolicy {

    /**
     * Client → upstream filter. Returns a filtered copy to attach to the
     * upstream request. Auth/transport headers (Authorization, Content-Type,
     * Accept, Host, Content-Length, hop-by-hop) are NEVER forwarded: the
     * gateway sets those itself, and the upstream credential comes from the
     * route config, not the caller.
     */
    Map<String, String> toUpstream(ProtocolKind kind, Map<String, String> clientHeaders);

    /**
     * Upstream → client filter. Returns the header NAMES whose values the
     * engine should copy onto the client response (first value per name).
     * Called after the upstream headers arrive, before the response commits.
     */
    List<String> toClient(Map<String, List<String>> upstreamHeaders);
}
