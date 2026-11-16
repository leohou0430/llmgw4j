package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only usage summary from the governance ledger (per-key requests / prompt / completion
 * / errors). Requires CATALOG_READ in scope. The ledger is not a financial ledger — these
 * are diagnostics for the control plane.
 */
@RestController
@RequestMapping("/control")
public class ControlUsageController {
    private final RequestAuthorizer authorizer;
    private final UsageLedger usage;

    public ControlUsageController(RequestAuthorizer authorizer, UsageLedger usage) {
        this.authorizer = authorizer;
        this.usage = usage;
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> usage(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId) {
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, null, false, Action.CATALOG_READ, new Target("t1", "p1")));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        usage.snapshot().forEach((keyId, summary) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requests", summary.requests());
            row.put("promptTokens", summary.promptTokens());
            row.put("completionTokens", summary.completionTokens());
            row.put("errors", summary.errors());
            out.put(keyId, row);
        });
        return ResponseEntity.ok(Map.of("usage", out));
    }
}