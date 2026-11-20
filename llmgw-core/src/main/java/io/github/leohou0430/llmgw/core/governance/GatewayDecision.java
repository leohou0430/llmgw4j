package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;

/**
 * Admission result. An allowed decision also carries the ALREADY PARSED request so the
 * data planes never parse the same JSON body a second time.
 * {@code budgetReservedMicroUsd} (v13) is the $ reservation the chain took at admission;
 * the data planes only forward it into the GatewayContext. {@code policyVersion}
 * (Chapter-9 §9.5.3) stamps WHICH governance rule set decided this admission, so every
 * judgment stays explainable and auditable.
 */
public record GatewayDecision(boolean allowed, int status, String reason, String keyId, String model, Route route,
		ParsedRequest parsed, long budgetReservedMicroUsd, String keyGroup, long groupBudgetReservedMicroUsd,
		String reservationId, String tenantId, String projectId, long policyVersion) {

	public GatewayDecision(boolean allowed, int status, String reason, String keyId, String model, Route route,
			ParsedRequest parsed, long budgetReservedMicroUsd, String keyGroup, long groupBudgetReservedMicroUsd,
			String reservationId) {
		this(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd, keyGroup,
				groupBudgetReservedMicroUsd, reservationId, "", "", 0);
	}

	public GatewayDecision withScope(String tenant, String project) {
		return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd,
				keyGroup, groupBudgetReservedMicroUsd, reservationId, tenant, project, policyVersion);
	}

	public GatewayDecision withReservation(String id) {
		return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd,
				keyGroup, groupBudgetReservedMicroUsd, id, tenantId, projectId, policyVersion);
	}

	/** Chapter-9 §9.5.3: stamp the deciding policy version onto the judgment. */
	public GatewayDecision withPolicyVersion(long version) {
		return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd,
				keyGroup, groupBudgetReservedMicroUsd, reservationId, tenantId, projectId, Math.max(0, version));
	}

	public GatewayDecision(boolean allowed, int status, String reason, String keyId, String model, Route route,
			ParsedRequest parsed, long budgetReservedMicroUsd, String keyGroup, long groupBudgetReservedMicroUsd) {
		this(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd, keyGroup,
				groupBudgetReservedMicroUsd, "", 0);
	}

	public GatewayDecision(boolean allowed, int status, String reason, String keyId, String model, Route route,
			ParsedRequest parsed, long budgetReservedMicroUsd, String keyGroup, long groupBudgetReservedMicroUsd,
			String reservationId, long policyVersion) {
		this(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd, keyGroup,
				groupBudgetReservedMicroUsd, reservationId, "", "", policyVersion);
	}

	public GatewayDecision {
		if (budgetReservedMicroUsd < 0) {
			budgetReservedMicroUsd = 0;
		}
		if (groupBudgetReservedMicroUsd < 0) {
			groupBudgetReservedMicroUsd = 0;
		}
		if (policyVersion < 0) {
			policyVersion = 0;
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
		return new GatewayDecision(allowed, status, reason, keyId, model, resolved, parsed, budgetReservedMicroUsd,
				keyGroup, groupBudgetReservedMicroUsd, reservationId, tenantId, projectId, policyVersion);
	}

	public GatewayDecision withBudgetReserved(long microUsd) {
		return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed, microUsd, keyGroup,
				groupBudgetReservedMicroUsd, reservationId, tenantId, projectId, policyVersion);
	}

	public GatewayDecision withGroup(String group, long groupReservedMicroUsd) {
		return new GatewayDecision(allowed, status, reason, keyId, model, route, parsed, budgetReservedMicroUsd, group,
				groupReservedMicroUsd, reservationId, tenantId, projectId, policyVersion);
	}
}
