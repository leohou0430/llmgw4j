package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.RequestLogSink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory ring (default 1000 entries). Writes happen on the
 * governance side car, never on the request thread; readers get an immutable
 * newest-first snapshot. Capacity keeps memory O(1) -- this is a debugging
 * surface, not a warehouse (swap the SPI for a real backend at scale).
 */
public class InMemoryRequestLog implements RequestLogSink {

    private final Deque<RequestLog> ring;
    private final int capacity;
    private int total;

    public InMemoryRequestLog(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
    }

    @Override
    public synchronized void log(RequestLog entry) {
        if (entry == null) {
            return;
        }
        if (ring.size() >= capacity) {
            ring.pollLast();
        }
        ring.addFirst(entry);
        total++;
    }

    @Override
    public synchronized List<RequestLog> recent(int limit) {
        int n = Math.min(Math.max(0, limit), ring.size());
        List<RequestLog> out = new ArrayList<>(n);
        var iterator = ring.iterator();
        for (int i = 0; i < n && iterator.hasNext(); i++) {
            out.add(iterator.next());
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized int size() {
        return ring.size();
    }

    /** Total entries ever logged (monotonic; for the console health strip). */
    public synchronized long totalLogged() {
        return total;
    }
}
