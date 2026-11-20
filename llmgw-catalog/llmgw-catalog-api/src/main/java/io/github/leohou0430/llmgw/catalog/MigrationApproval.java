package io.github.leohou0430.llmgw.catalog;

/**
 * An explicit, human-reviewed decision to map a legacy owner into a tenant/project.
 * Without an approved record the legacy resources are not shared (ownership is never
 * guessed).
 */
public record MigrationApproval(String legacyOwner, String tenantId, String projectId, String reviewerId,
		boolean approved) {
}