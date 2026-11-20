package io.github.leohou0430.llmgw.control;

/**
 * Raised when a root key is configured but cannot authenticate stored ciphertext. The key
 * is unusable for this database, so callers must fail closed exactly like a missing key
 * instead of writing data under an unrelated key.
 */
public class RootKeyMismatchException extends RootKeyMissingException {

	public RootKeyMismatchException(String message, Throwable cause) {
		super(message);
		initCause(cause);
	}

}
