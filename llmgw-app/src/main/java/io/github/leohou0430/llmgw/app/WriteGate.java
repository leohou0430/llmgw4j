package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard;
import io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Maps the storage cap/free-space guard to a 503 before any management write is persisted. */
final class WriteGate {

    private WriteGate() {
    }

    static void check(StorageGuard guard) {
        try {
            guard.requireWritable();
        } catch (StorageLimitException limit) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, limit.getMessage());
        }
    }
}
