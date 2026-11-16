package io.github.leohou0430.llmgw.catalog;

/** Raised when a caller's project is not authorized to reach a catalog source. */
public class CatalogAccessException extends RuntimeException {

    public CatalogAccessException(String message) {
        super(message);
    }
}