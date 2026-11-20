package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.AdminTokenDecision;
import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.local.DefaultAdminTokenPolicy;
import io.github.leohou0430.llmgw.control.local.RestrictedNetworks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenGuardTest {

	private static AdminTokenGuard guard(AdminTokenMode mode, String networks, boolean configured) {
		return new AdminTokenGuard(new DefaultAdminTokenPolicy(), mode, new RestrictedNetworks(networks), configured);
	}

	@Test
	void disabledDeniesLoopbackWhenTokenConfigured() {
		AdminTokenDecision decision = guard(AdminTokenMode.DISABLED, "", true).evaluate("127.0.0.1");

		assertFalse(decision.allowed());
		assertFalse(decision.emergencyPlatform());
	}

	@Test
	void migrationAllowsLoopbackAsEmergency() {
		AdminTokenDecision decision = guard(AdminTokenMode.MIGRATION, "", true).evaluate("127.0.0.1");

		assertTrue(decision.allowed());
		assertTrue(decision.emergencyPlatform());
	}

	@Test
	void migrationAllowsRestrictedNetworkButRejectsPublic() {
		AdminTokenGuard guard = guard(AdminTokenMode.MIGRATION, "10.1.0.0/16", true);

		assertTrue(guard.evaluate("10.1.2.3").allowed());
		assertFalse(guard.evaluate("203.0.113.5").allowed());
	}

	@Test
	void unconfiguredDemoIsNotGated() {
		AdminTokenDecision decision = guard(AdminTokenMode.DISABLED, "", false).evaluate("203.0.113.5");

		assertTrue(decision.allowed());
		assertFalse(decision.emergencyPlatform());
	}

}
