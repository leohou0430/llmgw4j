package io.github.leohou0430.llmgw.core.routing;

import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.util.List;

public interface Router {
    /** Pre-v12 signature; resolves as the chat protocol. */
    Route resolve(String model);

    /** Protocol-aware resolution (pass-through parity: protocol is routing identity). */
    Route resolve(ProtocolKind kind, String model);

    /** Route by id; null when absent (video sticky routing). */
    Route byId(String id);

    List<Route> fallbacks(Route primary);

    List<Route> all();
}
