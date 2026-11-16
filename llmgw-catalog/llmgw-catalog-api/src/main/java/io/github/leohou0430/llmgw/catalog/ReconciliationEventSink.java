package io.github.leohou0430.llmgw.catalog;

/** Receives sanitized reconciliation events for manual follow-up. */
@FunctionalInterface
public interface ReconciliationEventSink {

    void pending(ReconciliationEvent event);
}