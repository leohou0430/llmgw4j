package io.github.leohou0430.llmgw.identity;

/** Issues and verifies CSRF tokens bound to a session; verification is constant-time. */
public interface CsrfTokenService {

    String issue(String sessionId);

    boolean verify(String sessionId, String token);
}