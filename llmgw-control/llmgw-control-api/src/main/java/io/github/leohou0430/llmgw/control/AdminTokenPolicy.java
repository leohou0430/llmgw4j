package io.github.leohou0430.llmgw.control;

/**
 * Decides whether a legacy X-Admin-Token request may act. Disabled by default; migration
 * mode allows only a loopback client or a client on the restricted admin network, always
 * as an audited emergency platform action. This is orthogonal to caller JWT verification.
 */
@FunctionalInterface
public interface AdminTokenPolicy {

    /**
     * @param mode              current mode (disabled unless explicitly set to migration)
     * @param clientAddress     caller address (used for the loopback check)
     * @param restrictedNetwork whether the caller is inside the configured admin network
     */
    AdminTokenDecision evaluate(AdminTokenMode mode, String clientAddress, boolean restrictedNetwork);
}