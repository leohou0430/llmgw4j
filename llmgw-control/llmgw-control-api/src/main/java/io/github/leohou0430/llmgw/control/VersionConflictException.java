package io.github.leohou0430.llmgw.control;

/**
 * Raised when a publish/rollback was based on a stale expected version (concurrent
 * overwrite).
 */
public class VersionConflictException extends RuntimeException {

	public VersionConflictException(String message) {
		super(message);
	}

}