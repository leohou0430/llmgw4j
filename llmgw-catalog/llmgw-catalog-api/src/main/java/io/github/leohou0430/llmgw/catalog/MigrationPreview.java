package io.github.leohou0430.llmgw.catalog;

import java.util.List;

/**
 * Outcome of a migration preview or apply: the entries that would be / were migrated, the
 * legacy ids rejected for lack of an approved mapping, and the legacy ids that conflict
 * with an existing catalog entry (rejected, never auto-merged).
 */
public record MigrationPreview(List<ResourceEntry> toMigrate, List<String> rejected, List<String> conflicts) {

	public MigrationPreview {
		toMigrate = List.copyOf(toMigrate);
		rejected = List.copyOf(rejected);
		conflicts = List.copyOf(conflicts);
	}
}