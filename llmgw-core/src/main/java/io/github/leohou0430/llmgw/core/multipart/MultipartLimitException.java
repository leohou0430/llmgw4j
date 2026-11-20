package io.github.leohou0430.llmgw.core.multipart;

import java.io.IOException;

/**
 * A multipart body exceeded a configured bound. Extends IOException so the streaming scan
 * propagates it through the pull source's checked contract; the {@link Limit} tells the
 * engine which 413 reason to render (T2.7).
 */
public class MultipartLimitException extends IOException {

	public enum Limit {

		FILE, MULTIPART, METADATA

	}

	private final Limit limit;

	public MultipartLimitException(Limit limit, String message) {
		super(message);
		this.limit = limit;
	}

	public Limit limit() {
		return limit;
	}

}