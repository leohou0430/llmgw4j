package io.github.leohou0430.llmgw.catalog;

/** Deletion lifecycle of an upstream resource, tracked separately from the catalog entry. */
public enum DeletionState {
    /** Requested; the upstream has not yet confirmed. */
    PENDING,
    /** The upstream confirmed the delete. */
    CONFIRMED,
    /** The outcome is unknown (timeout / failure); needs manual reconciliation. */
    UNKNOWN
}