package io.github.leohou0430.llmgw.persistence.sqlite;

/** Raised when a write would exceed the database size cap or fall under the free-disk margin. */
public class StorageLimitException extends RuntimeException {

    public StorageLimitException(String message) {
        super(message);
    }
}