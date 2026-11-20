package io.github.leohou0430.llmgw.catalog;

/**
 * Checks whether the resource is gone from its fixed upstream source (manual
 * reconciliation).
 */
@FunctionalInterface
public interface UpstreamDeleteChecker {

	boolean deleted(String sourceRoute, String upstreamId);

}