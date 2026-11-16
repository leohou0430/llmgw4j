package io.github.leohou0430.llmgw.catalog;

/** Deletes the resource on its fixed upstream source; throws on failure/timeout (non-idempotent). */
@FunctionalInterface
public interface UpstreamDeleter {

    void delete(String sourceRoute, String upstreamId);
}