package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.AuditSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free bounded audit ring. The previous ArrayDeque + synchronized pair
 * serialized EVERY finished request on one global monitor, which put a lock
 * convoy on the side-car path under load.
 */
public class InMemoryAuditSink implements AuditSink {

    private static final int MAX_EVENTS = 5000;
    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    @Override
    public void emit(AuditEvent event) {
        events.addFirst(event);
        if (size.incrementAndGet() > MAX_EVENTS && events.pollLast() != null) {
            size.decrementAndGet();
        }
    }

    @Override
    public List<AuditEvent> recent(int limit) {
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent event : events) {
            if (out.size() >= limit) {
                break;
            }
            out.add(event);
        }
        return out;
    }
}
