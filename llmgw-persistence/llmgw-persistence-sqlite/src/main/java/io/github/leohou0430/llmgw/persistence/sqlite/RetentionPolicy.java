package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Retention policy for the catalog database. Defaults keep deleted-entry tombstones and
 * detailed audit rows for 30 days (configurable). A purge removes only OLD tombstones and
 * OLD audit rows in one transaction — it never removes an active resource and never
 * silently drops recent audit.
 */
public record RetentionPolicy(long tombstoneDays, long auditDays) {
	private static final long DAY_MS = 86_400_000L;

	public RetentionPolicy {
		if (tombstoneDays < 0 || auditDays < 0) {
			throw new IllegalArgumentException("retention days must be non-negative");
		}
	}

	public static RetentionPolicy defaults() {
		return new RetentionPolicy(30, 30);
	}

	/** Purges expired tombstones and audit rows; returns the number of removed rows. */
	public int purge(DatabaseContext database, long nowEpochMs) {
		long auditCutoff = nowEpochMs - auditDays * DAY_MS;
		long tombstoneCutoff = nowEpochMs - tombstoneDays * DAY_MS;
		try {
			return database.transaction(connection -> {
				int removed = 0;
				try (PreparedStatement statement = connection
					.prepareStatement("DELETE FROM audit_event WHERE created_at < ?")) {
					statement.setLong(1, auditCutoff);
					removed += statement.executeUpdate();
				}
				try (PreparedStatement statement = connection
					.prepareStatement("DELETE FROM resource_catalog WHERE deleted = 1 AND created_at < ?")) {
					statement.setLong(1, tombstoneCutoff);
					removed += statement.executeUpdate();
				}
				return removed;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("retention purge failed", failure);
		}
	}
}
