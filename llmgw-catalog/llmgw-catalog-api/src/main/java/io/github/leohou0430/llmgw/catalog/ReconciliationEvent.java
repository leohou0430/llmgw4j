package io.github.leohou0430.llmgw.catalog;

/**
 * Sanitized "needs reconciliation" record emitted when an upstream object exists but the
 * catalog commit failed. It carries only non-secret identity: project, gateway id, type,
 * upstream id, a fixed reason code and the observation time — never credentials, raw
 * request bodies or metadata values.
 */
public record ReconciliationEvent(String projectId, String gatewayId, String type, String upstreamId, String reason,
		long observedAt) {
}