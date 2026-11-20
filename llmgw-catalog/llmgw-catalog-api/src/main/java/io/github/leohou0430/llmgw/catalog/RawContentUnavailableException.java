package io.github.leohou0430.llmgw.catalog;

/**
 * Raised when raw upstream content is requested: it is never persisted nor downloadable.
 */
public class RawContentUnavailableException extends RuntimeException {

	public RawContentUnavailableException(String message) {
		super(message);
	}

}