package io.github.leohou0430.llmgw.catalog;

/** Immutable snapshot of a resource's deletion lifecycle and the last observation. */
public record DeletionRecord(
        String projectId,
        String gatewayId,
        DeletionState state,
        int attempts,
        long observedAt) {
}