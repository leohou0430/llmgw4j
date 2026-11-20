package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.AdminTokenDecision;
import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.AdminTokenPolicy;
import io.github.leohou0430.llmgw.control.local.RestrictedNetworks;

import java.util.Objects;

/**
 * Applies the legacy X-Admin-Token policy to the old /admin surface. A deployment with no
 * legacy token configured is the explicit local demo and is left unchanged; once a token
 * or read token exists, migration mode and the operator's restricted network decide
 * access.
 */
public final class AdminTokenGuard {

	private final AdminTokenPolicy policy;

	private final AdminTokenMode mode;

	private final RestrictedNetworks restrictedNetworks;

	private final boolean legacyTokenConfigured;

	public AdminTokenGuard(AdminTokenPolicy policy, AdminTokenMode mode, RestrictedNetworks restrictedNetworks,
			boolean legacyTokenConfigured) {
		this.policy = Objects.requireNonNull(policy, "policy");
		this.mode = Objects.requireNonNull(mode, "mode");
		this.restrictedNetworks = Objects.requireNonNull(restrictedNetworks, "restrictedNetworks");
		this.legacyTokenConfigured = legacyTokenConfigured;
	}

	public boolean legacyTokenConfigured() {
		return legacyTokenConfigured;
	}

	public AdminTokenDecision evaluate(String clientAddress) {
		if (!legacyTokenConfigured) {
			return new AdminTokenDecision(true, false, "legacy admin token is not configured");
		}
		return policy.evaluate(mode, clientAddress, restrictedNetworks.matches(clientAddress));
	}

}
