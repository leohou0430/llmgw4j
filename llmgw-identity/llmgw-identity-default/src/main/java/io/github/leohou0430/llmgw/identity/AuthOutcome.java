package io.github.leohou0430.llmgw.identity.local;

/** Result of authorizing a control-plane request. */
public enum AuthOutcome {

	ALLOW, UNAUTHENTICATED, CSRF_FAILED, FORBIDDEN

}