package io.github.leohou0430.llmgw.core.routing;

/**
 * Task-route stickiness SPI (v13): an async job (video, batch, ...) only
 * exists on the upstream that created it, so polling and content requests
 * MUST go back to that route. The default implementation is the bounded
 * in-memory map ({@link InMemoryTaskRouteSticky}); the Redis pack swaps in a
 * cross-replica store so replica B can poll a job created on replica A.
 */
public interface TaskRouteSticky {

    void bind(String taskId, String routeId);

    /** The route id the task was created on, or null when unknown/expired. */
    String resolve(String taskId);
}
