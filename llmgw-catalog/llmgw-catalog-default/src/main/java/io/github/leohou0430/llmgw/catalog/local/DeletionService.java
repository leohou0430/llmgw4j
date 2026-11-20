package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.DeletionRecord;
import io.github.leohou0430.llmgw.catalog.DeletionState;
import io.github.leohou0430.llmgw.catalog.RawContentUnavailableException;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.catalog.UpstreamDeleteChecker;
import io.github.leohou0430.llmgw.catalog.UpstreamDeleter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Deletion state machine, tracked separately from the catalog entry. A delete request is
 * PENDING, then CONFIRMED only when the upstream confirms; a failure/timeout leaves it
 * UNKNOWN and is never auto-retried (a non-idempotent delete). An unknown result can be
 * resolved by a manual reconciliation check. Removing a catalog entry does not touch this
 * machine, and raw upstream content is never persisted or downloadable.
 */
public final class DeletionService {

	private final ResourceCatalog catalog;

	private final LongSupplier clock;

	private final Map<String, DeletionRecord> records = new ConcurrentHashMap<>();

	public DeletionService(ResourceCatalog catalog, LongSupplier clock) {
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	private static String key(String projectId, String gatewayId) {
		return projectId + '\u0000' + gatewayId;
	}

	public DeletionRecord requestDelete(String projectId, String gatewayId, UpstreamDeleter deleter) {
		Objects.requireNonNull(deleter, "deleter");
		ResourceEntry entry = catalog.get(projectId, gatewayId)
			.orElseThrow(() -> new IllegalArgumentException("unknown resource: " + gatewayId));

		records.put(key(projectId, gatewayId),
				new DeletionRecord(projectId, gatewayId, DeletionState.PENDING, 1, clock.getAsLong()));
		DeletionState outcome;
		try {
			deleter.delete(entry.sourceRoute(), entry.upstreamId());
			outcome = DeletionState.CONFIRMED;
		}
		catch (RuntimeException failure) {
			outcome = DeletionState.UNKNOWN;
		}
		DeletionRecord record = new DeletionRecord(projectId, gatewayId, outcome, 1, clock.getAsLong());
		records.put(key(projectId, gatewayId), record);
		return record;
	}

	public DeletionRecord reconcile(String projectId, String gatewayId, UpstreamDeleteChecker checker) {
		Objects.requireNonNull(checker, "checker");
		DeletionRecord current = records.get(key(projectId, gatewayId));
		if (current == null) {
			throw new IllegalStateException("no deletion in progress: " + gatewayId);
		}
		ResourceEntry entry = catalog.get(projectId, gatewayId)
			.orElseThrow(() -> new IllegalArgumentException("unknown resource: " + gatewayId));
		DeletionState next = checker.deleted(entry.sourceRoute(), entry.upstreamId()) ? DeletionState.CONFIRMED
				: current.state();
		DeletionRecord updated = new DeletionRecord(projectId, gatewayId, next, current.attempts() + 1,
				clock.getAsLong());
		records.put(key(projectId, gatewayId), updated);
		return updated;
	}

	public DeletionState state(String projectId, String gatewayId) {
		DeletionRecord record = records.get(key(projectId, gatewayId));
		return record == null ? null : record.state();
	}

	public void downloadRaw(String projectId, String gatewayId) {
		throw new RawContentUnavailableException("raw upstream content is neither persisted nor downloadable");
	}

}