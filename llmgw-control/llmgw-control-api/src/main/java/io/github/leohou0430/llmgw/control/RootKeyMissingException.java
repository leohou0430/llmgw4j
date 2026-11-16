package io.github.leohou0430.llmgw.control;

/** Raised when the at-rest root key is not available; callers must fail closed. */
public class RootKeyMissingException extends RuntimeException {

    public RootKeyMissingException(String message) {
        super(message);
    }
}