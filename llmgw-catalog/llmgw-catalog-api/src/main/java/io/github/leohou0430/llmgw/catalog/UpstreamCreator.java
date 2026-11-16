package io.github.leohou0430.llmgw.catalog;

/**
 * Creates the object on the fixed upstream source and returns its upstream id. Called
 * exactly once per creation attempt: without an idempotency contract the service must
 * not auto-retry a create that may already have succeeded upstream.
 */
@FunctionalInterface
public interface UpstreamCreator {

    String create(CreationRequest request);

    class UpstreamCreationException extends RuntimeException {
        public UpstreamCreationException(String message) {
            super(message);
        }
    }
}