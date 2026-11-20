package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.AdminTokenDecision;
import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.AdminTokenPolicy;

/**
 * Legacy X-Admin-Token policy. Disabled unless the operator explicitly enables migration
 * mode; even then only a loopback or restricted-admin-network caller is accepted, and
 * only as an audited emergency platform action (no scope-less backdoor, no normal
 * session).
 */
public final class DefaultAdminTokenPolicy implements AdminTokenPolicy {

	@Override
	public AdminTokenDecision evaluate(AdminTokenMode mode, String clientAddress, boolean restrictedNetwork) {
		if (mode == null || mode == AdminTokenMode.DISABLED) {
			return AdminTokenDecision.deny("X-Admin-Token remote management is disabled");
		}
		if (isLoopback(clientAddress) || restrictedNetwork) {
			return AdminTokenDecision
				.emergency("migration-mode emergency platform action (loopback/restricted admin network)");
		}
		return AdminTokenDecision.deny("migration mode is limited to the local host or restricted admin network");
	}

	private static boolean isLoopback(String address) {
		return address != null
				&& (address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"));
	}

}