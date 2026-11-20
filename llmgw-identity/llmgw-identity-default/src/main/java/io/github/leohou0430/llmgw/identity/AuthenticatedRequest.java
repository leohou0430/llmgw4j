package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;

/** The security-relevant parts of an inbound control-plane request. */
public record AuthenticatedRequest(String sessionId, String csrfToken, boolean mutating, Action action, Target target) {
}