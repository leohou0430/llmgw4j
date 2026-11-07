package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;

/**
 * Admission result. An allowed decision also carries the ALREADY PARSED request
 * so the data planes never parse the same JSON body a second time.
 */
public record GatewayDecision(
        boolean allowed,
        int status,
        String reason,
        String keyId,
        String model,
        Route route,
        ParsedRequest parsed) {

    public static GatewayDecision deny(int status, String reason) {
        return new GatewayDecision(false, status, reason, "", "", null, null);
    }

    public static GatewayDecision allow(String keyId, ParsedRequest parsed, Route route) {
        return new GatewayDecision(true, 200, "ok", keyId, parsed.model(), route, parsed);
    }
}
