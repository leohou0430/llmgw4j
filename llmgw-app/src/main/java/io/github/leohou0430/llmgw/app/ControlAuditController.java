package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.AuthOutcome;
import io.github.leohou0430.llmgw.identity.local.AuthenticatedRequest;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Read-only, masked management audit for a project. Requires AUDIT_READ (project viewers may
 * read their project's audit; a cross-scope caller is denied).
 */
@RestController
@RequestMapping("/control")
public class ControlAuditController {
    private final RequestAuthorizer authorizer;
    private final AuditLog audit;

    public ControlAuditController(RequestAuthorizer authorizer, AuditLog audit) {
        this.authorizer = authorizer;
        this.audit = audit;
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> recent(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId) {
        Target target = new Target("t1", "p1");
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, null, false, Action.AUDIT_READ, target));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
        return ResponseEntity.ok(Map.of("events", audit.recent("p1", 50)));
    }
}