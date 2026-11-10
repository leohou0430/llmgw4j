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

    /** Scoped methods are mandatory for data-plane resource authorization. */
    default void bind(String tenantId, String protocol, String taskId, String routeId) {
        bind(scopedId(tenantId, protocol, taskId), routeId);
    }

    default String resolve(String tenantId, String protocol, String taskId) {
        return resolve(scopedId(tenantId, protocol, taskId));
    }

    private static String scopedId(String tenantId, String protocol, String taskId) {
        if (tenantId == null || tenantId.isBlank() || protocol == null || protocol.isBlank()
                || taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("resource scope must not be blank");
        }
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return "v2:" + encoder.encodeToString(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ":" + encoder.encodeToString(protocol.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ":" + encoder.encodeToString(taskId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The route id the task was created on, or null when unknown/expired. */
    String resolve(String taskId);
}
