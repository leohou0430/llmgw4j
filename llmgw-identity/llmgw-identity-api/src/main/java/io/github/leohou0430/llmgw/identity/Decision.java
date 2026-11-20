package io.github.leohou0430.llmgw.identity;

/** Result of an authorization check. */
public enum Decision {

	ALLOW, DENY;

	public boolean allowed() {
		return this == ALLOW;
	}

}