package io.github.leohou0430.llmgw.control;

/** How the legacy X-Admin-Token header is treated by the new control plane. */
public enum AdminTokenMode {
    /** Default: X-Admin-Token can never perform remote management. */
    DISABLED,
    /** Explicit migration mode: only local / restricted admin network, audited, emergency-only. */
    MIGRATION
}