package io.github.leohou0430.llmgw.core.routing;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Video task stickiness: an async video job only exists on the upstream that
 * created it, so polling and content requests MUST go back to that route.
 * Bounded by entry count and TTL — tasks are finite-lived upstream jobs, and
 * an unbounded map would grow with every create call. Expired/pinned-over
 * entries are pruned on insert; a miss simply falls back to protocol wildcard
 * routing (correct for single-upstream deployments and post-restart polls).
 */
public final class StickyTaskRoutes {

    private record Entry(String routeId, long expiresAtNanos) {
    }

    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long ttlNanos;

    public StickyTaskRoutes(int maxEntries, int ttlSeconds) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlNanos = Math.max(60, ttlSeconds) * 1_000_000_000L;
    }

    public void bind(String taskId, String routeId) {
        if (taskId == null || taskId.isBlank() || routeId == null || routeId.isBlank()) {
            return;
        }
        if (tasks.size() >= maxEntries) {
            prune();
        }
        tasks.put(taskId, new Entry(routeId, System.nanoTime() + ttlNanos));
    }

    /** The route id the task was created on, or null when unknown/expired. */
    public String resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Entry entry = tasks.get(taskId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtNanos() < System.nanoTime()) {
            tasks.remove(taskId);
            return null;
        }
        return entry.routeId();
    }

    private void prune() {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<String, Entry>> it = tasks.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expiresAtNanos() < now) {
                it.remove();
            }
        }
        // Still full after expiry pruning: drop oldest inserts (iteration order
        // of ConcurrentHashMap is unspecified but stable enough for a bound).
        while (tasks.size() >= maxEntries) {
            Iterator<Map.Entry<String, Entry>> it = tasks.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            it.next();
            it.remove();
        }
    }
}
