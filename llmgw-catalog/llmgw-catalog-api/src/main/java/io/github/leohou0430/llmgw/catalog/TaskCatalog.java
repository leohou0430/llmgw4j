package io.github.leohou0430.llmgw.catalog;

import java.util.Optional;

/**
 * Directory for upstream async tasks only (batch / video). It tracks directory, metadata,
 * observable status, paging and cancel pass-through — it is not a gateway
 * executor/queue/recovery scheduler and never impersonates a real provider's task
 * execution.
 */
public interface TaskCatalog {

	/** Registers an async task; non-async types are rejected before any access. */
	ResourceEntry register(ResourceEntry task);

	Optional<ResourceEntry> get(String projectId, String gatewayId);

	/**
	 * Forwards cancel to the fixed upstream source, then marks the entry cancelled on
	 * confirmation.
	 */
	boolean cancel(String projectId, String gatewayId, TaskCanceller canceller);

	CatalogPage page(String tenantId, String projectId, PageRequest request, String cursor);

}