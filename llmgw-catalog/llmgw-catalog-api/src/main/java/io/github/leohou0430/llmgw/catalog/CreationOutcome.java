package io.github.leohou0430.llmgw.catalog;

/**
 * Result of a catalog creation attempt. {@code Confirmed} means the upstream object
 * exists AND the catalog entry committed. {@code Unknown} means the upstream object was
 * created but the catalog commit failed — the outcome is genuinely unknown and needs
 * reconciliation (never reported as success). {@code Failed} means the upstream did not
 * create the object.
 */
public sealed interface CreationOutcome
		permits CreationOutcome.Confirmed, CreationOutcome.Unknown, CreationOutcome.Failed {

	record Confirmed(ResourceEntry entry) implements CreationOutcome {
	}

	record Unknown(String gatewayId, String upstreamId, String reason) implements CreationOutcome {
	}

	record Failed(String reason) implements CreationOutcome {
	}

}