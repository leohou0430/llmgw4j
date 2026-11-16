package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.AdminTokenDecision;
import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.AdminTokenPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.8 seam: the legacy X-Admin-Token is disabled by default; only explicit migration mode
 * allows a loopback / restricted-admin-network caller, always as an audited emergency
 * platform action. A public client is denied even in migration mode.
 */
class AdminTokenPolicyTest {

    private final AdminTokenPolicy policy = new DefaultAdminTokenPolicy();

    @Test
    void disabledRejectsEverything() {
        AdminTokenDecision decision = policy.evaluate(AdminTokenMode.DISABLED, "127.0.0.1", true);
        assertFalse(decision.allowed());
        assertFalse(decision.emergencyPlatform());
    }

    @Test
    void migrationAllowsLoopbackAsEmergency() {
        AdminTokenDecision decision = policy.evaluate(AdminTokenMode.MIGRATION, "127.0.0.1", false);
        assertTrue(decision.allowed());
        assertTrue(decision.emergencyPlatform());
    }

    @Test
    void migrationAllowsRestrictedAdminNetwork() {
        AdminTokenDecision decision = policy.evaluate(AdminTokenMode.MIGRATION, "10.1.2.3", true);
        assertTrue(decision.allowed());
        assertTrue(decision.emergencyPlatform());
    }

    @Test
    void migrationRejectsPublicClient() {
        assertFalse(policy.evaluate(AdminTokenMode.MIGRATION, "203.0.113.5", false).allowed());
    }
}