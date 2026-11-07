package io.github.leohou0430.llmgw.core.governance;

import java.time.Instant;
import java.util.Map;

public interface AuditSink {

    record AuditEvent(Instant at, String keyId, String event, Map<String, Object> detail) {
    }

    void emit(AuditEvent event);

    java.util.List<AuditEvent> recent(int limit);
}
