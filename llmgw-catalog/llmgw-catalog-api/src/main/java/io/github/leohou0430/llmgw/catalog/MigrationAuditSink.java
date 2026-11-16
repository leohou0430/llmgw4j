package io.github.leohou0430.llmgw.catalog;

/** Records each migration decision (accept / reject / conflict) for audit. */
@FunctionalInterface
public interface MigrationAuditSink {

    void record(String action, String legacyId, String detail);
}