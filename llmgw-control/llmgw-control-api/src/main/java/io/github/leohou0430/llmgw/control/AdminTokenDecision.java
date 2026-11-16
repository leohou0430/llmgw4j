package io.github.leohou0430.llmgw.control;

/**
 * Outcome of evaluating a legacy X-Admin-Token request. {@code emergencyPlatform} marks an
 * accepted migration-mode request as an audited emergency platform action (not a normal
 * login session and never a scope-less backdoor).
 */
public record AdminTokenDecision(boolean allowed, boolean emergencyPlatform, String reason) {

    public static AdminTokenDecision deny(String reason) {
        return new AdminTokenDecision(false, false, reason);
    }

    public static AdminTokenDecision emergency(String reason) {
        return new AdminTokenDecision(true, true, reason);
    }
}