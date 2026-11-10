package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;

/**
 * Admission result. An allowed decision also carries the ALREADY PARSED request
 * so the data planes never parse the same JSON body a second time.
 * {@code budgetReservedMicroUsd} (v13) is the $ reservation the chain took at
 * admission; the data planes only forward it into the GatewayContext.
 */
public record GatewayDecision(
        boolean allowed,
        int status,
        String reason,
        String keyId,
        String model,
        Route route,
        ParsedRequest parsed,
        long budgetReservedMicroUsd,
        String keyGroup,
        long groupBudgetReservedMicroUsd) {

    public GatewayDecision {
        if (budgetReservedMicroUsd < 0) {
            budgetReservedMicroUsd = 0;
        }
        if (groupBudgetReservedMicroUsd < 0) {
            groupBudgetReservedMicroUsd = 0;
        }
        keyGroup = keyGroup == null ? "" : keyGroup;
    }

    public static GatewayDecision deny(int status, String reason) {
        return new GatewayDecision(false, status, reason, "", "", null, null, 0, "", 0);
    }

    public static GatewayDecision allow(String keyId, ParsedRequest parsed, Route route) {
        return new GatewayDecision(true, 200, "ok", keyId, parsed.model(), route, parsed, 0, "", 0);
    }

    public GatewayDecision withRoute(Route resolved) {
        return new GatewayDecision(allowed, status, reason, keyId, model, resolved, parsed,
                budgetReservedMicroUsd, keyGroup, groupBudgetReservedMicroUsd);
    }

    public GatewayDecision withBudgetReserved(long microUsd) {
        return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed,
                microUsd, keyGroup, groupBudgetReservedMicroUsd);
    }

    public GatewayDecision withGroup(String group, long groupReservedMicroUsd) {
        return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed,
                budgetReservedMicroUsd, group, groupReservedMicroUsd);
    }
}
