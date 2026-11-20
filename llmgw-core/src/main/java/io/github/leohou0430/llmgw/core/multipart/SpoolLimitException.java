package io.github.leohou0430.llmgw.core.multipart;

import java.io.IOException;

/**
 * T2.6 spool admission failure. {@link Reason#TRANSFER} is a single transfer over its
 * byte cap, {@link Reason#TOTAL} is the per-process spool quota being exhausted (the
 * failed reservation is rolled back), {@link Reason#DISK} is the filesystem safety margin
 * not being met before a temp file is created.
 */
public class SpoolLimitException extends IOException {

	public enum Reason {

		TRANSFER, TOTAL, DISK

	}

	private final Reason reason;

	public SpoolLimitException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

}