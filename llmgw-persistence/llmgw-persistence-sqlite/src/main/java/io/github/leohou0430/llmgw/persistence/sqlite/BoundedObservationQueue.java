package io.github.leohou0430.llmgw.persistence.sqlite;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded observation queue for HTTP observations. When full, new events are dropped and
 * counted (never buffered without bound); {@link #dropped()} exposes the discard total so
 * operators can see loss. It is a diagnostic buffer, not a durable financial ledger.
 */
public final class BoundedObservationQueue {
    private final int capacity;
    private final Deque<String> buffer;
    private final AtomicInteger dropped = new AtomicInteger();

    public BoundedObservationQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public synchronized boolean offer(String event) {
        if (buffer.size() >= capacity) {
            dropped.incrementAndGet();
            return false;
        }
        buffer.addLast(event);
        return true;
    }

    public synchronized int size() {
        return buffer.size();
    }

    public int dropped() {
        return dropped.get();
    }
}