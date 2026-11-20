package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CreationOutcome;
import io.github.leohou0430.llmgw.catalog.CreationRequest;
import io.github.leohou0430.llmgw.catalog.ReconciliationEvent;
import io.github.leohou0430.llmgw.catalog.ReconciliationEventSink;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.catalog.UpstreamCreator;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Creates a resource on its fixed upstream source, then commits the catalog entry. The
 * result is {@code Confirmed} only when both succeed. If the upstream create succeeds but
 * the catalog commit fails, the outcome is {@code Unknown} and a sanitized reconciliation
 * event is emitted — the cross-system operation is not atomic and is never reported as
 * success. The upstream create is invoked exactly once; without an idempotency contract
 * it is never automatically resent.
 */
public final class CreationService {

	private final ResourceCatalog catalog;

	private final UpstreamCreator creator;

	private final ReconciliationEventSink sink;

	private final LongSupplier clock;

	public CreationService(ResourceCatalog catalog, UpstreamCreator creator, ReconciliationEventSink sink,
			LongSupplier clock) {
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.creator = Objects.requireNonNull(creator, "creator");
		this.sink = Objects.requireNonNull(sink, "sink");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public CreationOutcome create(CreationRequest request) {
		Objects.requireNonNull(request, "request");

		String upstreamId;
		try {
			upstreamId = creator.create(request);
		}
		catch (UpstreamCreator.UpstreamCreationException failure) {
			return new CreationOutcome.Failed(failure.getMessage());
		}

		long now = clock.getAsLong();
		ResourceEntry entry = new ResourceEntry(request.gatewayId(), request.projectId(), request.type(), upstreamId,
				request.sourceRoute(), request.provider(), request.configVersion(), now, "pending", request.metadata(),
				0L, false);
		try {
			return new CreationOutcome.Confirmed(catalog.create(entry));
		}
		catch (RuntimeException failure) {
			sink.pending(new ReconciliationEvent(request.projectId(), request.gatewayId(), request.type(), upstreamId,
					"catalog-commit-failed", now));
			return new CreationOutcome.Unknown(request.gatewayId(), upstreamId, "catalog-commit-failed");
		}
	}

}