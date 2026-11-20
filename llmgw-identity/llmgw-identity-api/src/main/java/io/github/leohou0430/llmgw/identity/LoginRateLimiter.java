package io.github.leohou0430.llmgw.identity;

/** Login attempt rate limiting keyed by client identity (e.g. username+IP). */
public interface LoginRateLimiter {

	/**
	 * Records an attempt for {@code key} at {@code nowEpochMs}; returns false when over
	 * the limit.
	 */
	boolean allow(String key, long nowEpochMs);

}