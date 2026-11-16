package io.github.leohou0430.llmgw.catalog;

/** Raised when a pagination cursor is tampered with or bound to a different scope. */
public class CatalogCursorException extends RuntimeException {

    public CatalogCursorException(String message) {
        super(message);
    }
}